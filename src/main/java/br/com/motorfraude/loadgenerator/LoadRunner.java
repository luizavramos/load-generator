package br.com.motorfraude.loadgenerator;

import br.com.motorfraude.loadgenerator.scenarios.PlannedTransaction;
import br.com.motorfraude.loadgenerator.scenarios.Scenario;
import br.com.motorfraude.loadgenerator.scenarios.ScenarioPicker;
import br.com.motorfraude.loadgenerator.scenarios.TransactionFactory;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.HdrHistogram.Recorder;

/**
 * Executa a carga em <strong>modelo aberto</strong>: a requisição {@code i} é planejada para {@code início + i / tps}
 * e enviada assim que o horário chega, sem esperar as anteriores terminarem (cada envio roda numa thread virtual). A
 * latência de cada resposta é medida a partir do horário <em>planejado</em>, não do envio: se o alvo travar, ou se o
 * próprio gerador atrasar, o atraso entra na medida em vez de sumir (<em>coordinated omission</em>).
 *
 * <p>Ao passar de {@code maxInFlight} requisições em voo, as novas são descartadas e contadas (o relatório mostra),
 * para um alvo parado não consumir a memória do gerador.
 */
public final class LoadRunner {

    private static final long HISTOGRAM_MAX_MICROS = 60_000_000L;

    private final Config config;
    private final Target target;
    private final Consumer<String> acceptedSink;

    public LoadRunner(Config config, Target target) {
        this(config, target, id -> {});
    }

    /** @param acceptedSink recebe o transactionId de cada resposta {@code 202 ACCEPTED}, inclusive do aquecimento */
    public LoadRunner(Config config, Target target, Consumer<String> acceptedSink) {
        this.config = config;
        this.target = target;
        this.acceptedSink = acceptedSink;
    }

    public Report run() {
        var clock = Clock.systemUTC();
        var random = config.seed() == null ? new Random() : new Random(config.seed());
        var picker = new ScenarioPicker(config.fraudRatio(), config.duplicateRatio(), random);
        var factory = new TransactionFactory(clock, random, Long.toString(clock.instant().getEpochSecond(), 36), config.accounts());

        long total = config.totalRequests();
        long warmupRequests = config.warmupRequests();
        long startNanos = System.nanoTime();
        long startCpuNanos = processCpuNanos();
        var bucket = new TokenBucket(config.tps(), startNanos);

        var recorder = new Recorder(HISTOGRAM_MAX_MICROS, 3);
        var statuses = new ConcurrentHashMap<String, LongAdder>();
        var scenarioCounts = new EnumMap<Scenario, LongAdder>(Scenario.class);
        for (var scenario : Scenario.values()) {
            scenarioCounts.put(scenario, new LongAdder());
        }
        var completed = new LongAdder();
        var errors = new LongAdder();
        var errorKinds = new ConcurrentHashMap<String, LongAdder>();
        var dropped = new LongAdder();
        var sent = new LongAdder();
        var inFlight = new AtomicInteger();
        long lastMeasuredSend = startNanos;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long index = 0;
            while (index < total) {
                long now = System.nanoTime();
                long due = bucket.takeAvailable(now, total - index);
                if (due == 0) {
                    LockSupport.parkNanos(Math.max(bucket.nanosUntilNext(now), 20_000L));
                    continue;
                }
                for (long k = 0; k < due; k++, index++) {
                    boolean measured = index >= warmupRequests;
                    var planned = factory.next(picker.next());
                    if (inFlight.get() >= config.maxInFlight()) {
                        if (measured) {
                            dropped.increment();
                        }
                        continue;
                    }
                    inFlight.incrementAndGet();
                    if (measured) {
                        sent.increment();
                        scenarioCounts.get(planned.scenario()).increment();
                        lastMeasuredSend = System.nanoTime();
                    }
                    long plannedNanos = bucket.plannedNanos(index);
                    executor.submit(() -> {
                        try {
                            exchange(planned, plannedNanos, measured, recorder, statuses, completed, errors, errorKinds);
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    });
                }
            }
        }

        long wallNanos = System.nanoTime() - startNanos;
        long cpuNanos = processCpuNanos() - startCpuNanos;
        double cpuCores = startCpuNanos < 0 || wallNanos <= 0 ? -1 : cpuNanos / (double) wallNanos;

        long measuredPlanned = config.measuredRequests();
        long measureStart = bucket.plannedNanos(warmupRequests);
        long interval = 1_000_000_000L / config.tps();
        double windowSeconds = Math.max(lastMeasuredSend - measureStart + interval, 1) / 1e9;
        double tpsSent = sent.sum() / windowSeconds;

        return new Report(
                config.tps(),
                config.duration(),
                measuredPlanned,
                sent.sum(),
                completed.sum(),
                dropped.sum(),
                errors.sum(),
                tpsSent,
                recorder.getIntervalHistogram(),
                sortedCounts(statuses),
                scenarioCounts(scenarioCounts),
                sortedCounts(errorKinds),
                cpuCores,
                Runtime.getRuntime().availableProcessors());
    }

    /** CPU acumulada do processo do gerador, em nanossegundos; -1 se a JVM não informa. */
    private static long processCpuNanos() {
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return -1;
    }

    private void exchange(
            PlannedTransaction planned,
            long plannedNanos,
            boolean measured,
            Recorder recorder,
            Map<String, LongAdder> statuses,
            LongAdder completed,
            LongAdder errors,
            Map<String, LongAdder> errorKinds) {
        Target.Response response;
        try {
            response = target.send(planned);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (measured) {
                errors.increment();
                errorKinds.computeIfAbsent(describe(e), key -> new LongAdder()).increment();
            }
            return;
        }
        long doneNanos = System.nanoTime();
        if ("ACCEPTED".equals(response.detail()) && response.status() == 202) {
            acceptedSink.accept(planned.transactionId());
        }
        if (measured) {
            completed.increment();
            statuses.computeIfAbsent(statusKey(response), key -> new LongAdder()).increment();
            long micros = Math.max(1, (doneNanos - plannedNanos) / 1000);
            recorder.recordValue(Math.min(micros, HISTOGRAM_MAX_MICROS));
        }
    }

    /** Classe e mensagem (curta) da exceção; só a causa mais interna, para agrupar erros iguais. */
    private static String describe(Throwable error) {
        var root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        var message = root.getMessage() == null ? "" : root.getMessage().replaceAll("\\s+", " ");
        return root.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message.substring(0, Math.min(message.length(), 80)));
    }

    private static String statusKey(Target.Response response) {
        return response.detail().isEmpty() ? Integer.toString(response.status()) : response.status() + " " + response.detail();
    }

    private static Map<String, Long> sortedCounts(Map<String, LongAdder> counts) {
        var sorted = new TreeMap<String, Long>();
        counts.forEach((key, adder) -> sorted.put(key, adder.sum()));
        return sorted;
    }

    private static Map<Scenario, Long> scenarioCounts(Map<Scenario, LongAdder> counts) {
        var result = new LinkedHashMap<Scenario, Long>();
        counts.forEach((scenario, adder) -> {
            if (adder.sum() > 0) {
                result.put(scenario, adder.sum());
            }
        });
        return result;
    }
}
