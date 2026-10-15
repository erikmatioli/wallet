# A stack inteira no GitHub Codespaces

Um Codespace é uma máquina na nuvem do GitHub com o repositório, o Docker e o VS Code (no navegador ou no seu
VS Code). A configuração está em [`.devcontainer/`](../.devcontainer/): ao abrir, ele sobe todos os serviços e o
console sozinho.

## O que é gratuito (conta pessoal do GitHub)

| Cota por mês | Rende |
|---|---|
| 120 horas de núcleo | **30 horas** na máquina de 4 núcleos e 16 GB (a que a stack inteira pede) |
| 15 GB-mês de disco | a stack ocupa ~15 GB: um Codespace guardado o mês inteiro gasta a cota quase toda |

Sem forma de pagamento cadastrada, o GitHub **não cobra**: quando a cota acaba, os Codespaces param até o mês
seguinte. Para conferir: <https://github.com/settings/billing> → *Codespaces*.

Para a cota render:
- **Pare o Codespace quando não estiver usando** (ele também para sozinho depois de 30 minutos sem uso; o tempo
  muda em <https://github.com/settings/codespaces> → *Default idle timeout*). Parado, não gasta horas, só disco.
- **Apague o Codespace** quando não for mais usá-lo (por exemplo, quando a VM da Oracle ficar pronta): é o disco
  guardado que consome a cota de armazenamento.

## Criar

1. No GitHub, abra o repositório → botão **Code** → aba **Codespaces** → **…** → **New with options**.
2. *Branch*: a que tem o `.devcontainer` (a `main`, depois do merge). *Machine type*: **4-core, 16 GB RAM**.
3. **Create codespace**. O VS Code abre no navegador; o Codespace instala o JDK 25, o Maven, o Node 22 e o Docker
   (alguns minutos) e depois sobe a stack.

Ao abrir, o VS Code ganha dois terminais: **stack**, que sobe os serviços um a um e termina com a tabela de status
(tudo `200`), e **console**, que fica rodando o `ng serve`. A primeira subida compila todas as imagens: **15 a 25
minutos** (no teste, a stack ficou pronta em ~8 minutos). Nas aberturas seguintes, sobe em poucos minutos: as
imagens já existem. A qualquer momento:

```bash
scripts/dev-stack.sh status    # containers e health
```

## Acessar

Aba **PORTS** do VS Code: cada serviço tem uma linha com o endereço (`https://<nome>-4200.app.github.dev`). O
ícone do globo abre no navegador.

| Porta | O quê |
|---|---|
| 4200 | Console (`demo-tenant` / `demo-secret-change-me-please`) |
| 8086 / 8087 | M-Wall do demo-tenant / segundo-tenant (este só com `scripts/dev-stack.sh up --segundo-tenant`) |
| 8025 | Mailpit: os códigos de login do app |
| 8080 | wallet-core (`/swagger-ui.html`) |
| 3000 / 16686 | Grafana / Jaeger |

As portas são **privadas**: só abrem para quem está logado no GitHub com a sua conta. No celular, entre no GitHub
pelo navegador antes de abrir o M-Wall. Não mude a visibilidade para *Public*: os serviços usam segredos de
desenvolvimento.

Como o endereço é HTTPS, o M-Wall instala como app ("Adicionar à tela inicial").

## Trabalhar

- **Depois de mudar o código de um serviço:** `scripts/dev-stack.sh up` (reconstrói o que mudou).
- **Console:** o `ng serve` já roda com recarga automática; editar `wallet-console/` atualiza a tela.
- **Testes:** `cd wallet-core && mvn verify`, `cd wallet-app && ./gradlew build`, como na sua máquina (os testes
  com Testcontainers usam o Docker do Codespace).
- **Claude Code:** `curl -fsSL https://claude.ai/install.sh | bash`, depois `claude` na raiz do repositório.
- **Seu VS Code em vez do navegador:** extensão *GitHub Codespaces* → *Connect to Codespace*.

## Se algo não subir

- O terminal **stack** mostra em que serviço parou; `(cd <serviço> && docker compose logs --tail 80)` mostra o
  porquê.
- Os terminais não apareceram, ou foram fechados: `bash .devcontainer/start.sh` (stack) e
  `bash .devcontainer/start.sh console` (console), cada um num terminal.
- `scripts/dev-stack.sh up` de novo continua de onde parou.
- Disco cheio (`no space left on device`): `docker builder prune -af` libera o cache de build.
