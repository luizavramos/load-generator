# load-generator

Gerador de carga sintética em modelo aberto, com cenários de fraude. Gera `target/load-generator.jar`.

```bash
./mvnw package
java -jar target/load-generator.jar --tps 300 --duration 60s --warmup 15s
```
Use `--help` para todas as opções. Localmente a escala de referência é 300 TPS; 8.000 e 25.000 TPS ficam para a nuvem.

## Antes de rodar

O alvo precisa estar de pé: por padrão o `receiver` em `http://localhost:8081` (`--target` troca), e o que vem depois dele (Kafka, motor e notificações) se quiser ver o fluxo completo. A forma mais simples é o `docker-compose.yml` do repositório https://github.com/luizavramos/fraud-detection-engine com o perfil `app`; o roteiro de teste de carga está no README de lá, seção "Teste de carga".

As contas e os clientes sintéticos vêm do banco legado de demonstração do compose (`--accounts` diz quantas usar). Com `--accepted-log` o gerador grava os `transactionId` aceitos, que o `auditor` usa para conferir que cada transação teve exatamente uma decisão.

Visão geral e resultados medidos: o README do repositório acima.
