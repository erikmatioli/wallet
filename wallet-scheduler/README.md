# wallet-scheduler

Agenda transferências e Pix para uma data futura e executa no dia, com retentativa e motivo
legível de cada falha. Escrito em **Kotlin**, com a mesma arquitetura hexagonal dos outros
projetos. As decisões estão na [ADR-001](docs/adr/001-servico-de-agendamento.md).

> **Estado:** esqueleto. A aplicação sobe, conecta no banco `scheduler` e responde ao health check.
> As regras de agendamento entram nos próximos passos da ADR.

## Stack
- Kotlin 2.3 (versão gerenciada pelo Spring Boot), compilando para a JVM 25.
- Spring Boot 4.1, `JdbcClient` (sem JPA), Flyway, PostgreSQL.
- Testes: JUnit 5, AssertJ, Testcontainers e ArchUnit (que lê o bytecode, então vale para Kotlin).

## Pacotes

```
br.com.walletscheduler
├── domain          regras e estados; só Kotlin/JDK
├── application     casos de uso e portas; sem Spring
├── adapter.in      API REST, consumidor de eventos do Pix, job de execução
├── adapter.out     clientes do wallet-core e do wallet-pix, persistência JDBC
└── config          ligação com o Spring
```

As dependências apontam sempre para dentro; o `ArchitectureTest` quebra o build se não apontarem.

## Rodar localmente

```bash
cd ../wallet-core && docker compose up -d     # Postgres, observabilidade e wallet-core
cd ../wallet-scheduler && docker compose up -d --build
curl localhost:8082/actuator/health           # {"status":"UP"}
```

O compose entra na rede do wallet-core e cria o banco `scheduler` com dois papéis:
`scheduler_owner` (Flyway) e `scheduler_app` (aplicação, sem DDL).

## Build e testes

```bash
mvn verify      # compila Kotlin, roda ArchUnit e o teste de subida (precisa de Docker)
```
