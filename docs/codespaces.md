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
| 8086 / 8087 | M-Wall do demo-tenant / segundo-tenant |
| 8025 | Mailpit: os códigos de login do app |
| 8080 | wallet-core (`/swagger-ui.html`) |
| 3000 / 16686 | Grafana / Jaeger |

As portas são **privadas**: só abrem num navegador logado no GitHub com a sua conta. Nelas, as chamadas que levam
`Authorization: Bearer` (o M-Wall depois de entrar, o "Try it out" do Swagger) recebem **401** do encaminhamento do
GitHub, antes de chegar ao serviço. Para usar o M-Wall, torne a porta dele pública; para chamar as APIs, use o
terminal do Codespace (`localhost`) ou traga as portas para o seu PC com
`gh codespace ports forward 8080:8080 8090:8090`.

### Tornar uma porta pública

Aba **PORTS** → botão direito na linha → **Port Visibility** → **Public** (ou, no terminal,
`gh codespace ports visibility 8086:public -c $CODESPACE_NAME`). Volte para *Private* ao terminar.

| Porta | Pública? |
|---|---|
| 8086 / 8087 (M-Wall) | pode, durante o uso: para entrar é preciso o código do Mailpit, que continua privado |
| 8090 (simulador do SPI) | só se precisar e por pouco tempo: quem tiver o endereço "manda Pix" para as contas de teste |
| 8080 (core), 4200 (console), 8025 (Mailpit) | **nunca**: aceitam o login de desenvolvimento escrito neste repositório, ou mostram os códigos de qualquer cliente |

- O navegador mostra uma vez por sessão o aviso **"Codespaces Access Port"** do GitHub: toque em **Continue**.
- **Ao religar o Codespace, todas as portas voltam a *Private*.** Torne a porta pública de novo e **copie um link
  novo** da aba PORTS: um link aberto enquanto a porta era privada fica preso no login do GitHub.

Como o endereço é HTTPS, o M-Wall instala como app ("Adicionar à tela inicial").

### Mandar um Pix "de outro banco"

O simulador do SPI (porta 8090) faz o papel do outro banco. No terminal do Codespace:

```bash
python3 wallet-pix/scripts/pixdev.py incoming --account 001000029 --taxid 52998224725 --amount 25.00
```

A conta é o número com o dígito, sem traço (`0001 / 00100002-9` vira `001000029`), e o CPF é o do titular. Pelo
navegador, abra `https://<nome>-8090.app.github.dev/simulate/messages` e, no Console do DevTools dessa aba, faça um
`fetch('/simulate/incoming', { method: 'POST', ... })` com o mesmo corpo (veja `wallet-pix/README.md`).

## Os dados

Todos os bancos (core, app, Pix, agendamentos, OTP) ficam no Postgres do core, num volume do Docker que está no disco
do Codespace: **parar e religar o Codespace não perde nada**. Perdem-se só o que fica em memória (os e-mails do
Mailpit, um Pix em andamento no simulador, a fila do LocalStack naquele instante).

Os dados somem quando o Codespace é **apagado**, à mão ou depois de 30 dias sem uso (*Default retention period*, em
<https://github.com/settings/codespaces>). Antes de apagar, ou de um *Rebuild Container*, faça um backup:

```bash
mkdir -p /workspaces/backup
for db in wallet app app_segundo pix scheduler otp; do
  docker exec wallet-core-postgres-1 pg_dump -U wallet_owner -d $db -Fc > /workspaces/backup/$db.dump
done
```

Para guardar fora do Codespace: botão direito na pasta `backup`, no explorador do VS Code → **Download**.

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
- `service "app-db-init-segundo" didn't complete successfully` num Codespace novo: era a corrida entre os dois
  inicializadores do wallet-app criando os mesmos usuários do Postgres, corrigida no `init-app-db.sh`. Em versões
  anteriores, `scripts/dev-stack.sh up --fast` de novo resolve.
