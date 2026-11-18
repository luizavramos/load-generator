# load-generator

Gerador de carga sintética em modelo aberto, com cenários de fraude. Gera `target/load-generator.jar`.

```bash
./mvnw package
java -jar target/load-generator.jar --tps 300 --duration 60s --warmup 15s
```
Use `--help` para todas as opções. Localmente a escala de referência é 300 TPS; 8.000 e 25.000 TPS ficam para a nuvem.
