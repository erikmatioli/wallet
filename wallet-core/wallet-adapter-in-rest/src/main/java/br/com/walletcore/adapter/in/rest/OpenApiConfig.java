package br.com.walletcore.adapter.in.rest;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Wallet Core API",
                version = "v1",
                description = "- Documentação das APIs de gerenciamento de carteiras, ledger e transações."
        ),
        servers = {
                @Server(url = "http://localhost:8080", description = "Ambiente de Desenvolvimento Local")
        }
)
public class OpenApiConfig {
}