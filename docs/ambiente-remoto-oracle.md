# Ambiente de desenvolvimento remoto na Oracle Cloud (Always Free)

Uma VM ARM gratuita da Oracle roda a stack inteira (core, Pix, agendamentos, OTP, app-api, M-Wall, console e a
observabilidade). O seu computador fica só com o editor e o navegador, e o acesso é pela **Tailscale**, sem abrir
portas na internet.

| Recurso | Always Free | A stack usa |
|---|---|---|
| VM Ampere A1 (ARM) | 4 vCPUs e 24 GB de RAM | ~6 GB rodando, ~10 GB compilando |
| Disco | 200 GB | ~40 GB com imagens e caches |
| Tráfego de saída | 10 TB/mês | uso de desenvolvimento |

Os limites do plano gratuito podem mudar: confira em <https://www.oracle.com/cloud/free/> antes de começar.

---

## 1. Conta na Oracle Cloud

1. Cadastre-se em <https://signup.cloud.oracle.com>.
2. **Região principal (home region):** é definitiva e só nela ficam os recursos gratuitos. A VM A1 às vezes falta
   nas regiões mais procuradas (São Paulo é uma delas); numa conta nova, **Brazil Southeast (Vinhedo)** costuma ter
   mais capacidade. Numa conta que já existe, a região é a que está: veja as dicas de capacidade no passo 3.
3. O cartão de crédito é só verificação: a conta gratuita não cobra.

**Conta que já existia:** confira em *Compute → Instances* e *Block Storage* se não há VM ou volume esquecido; o
limite gratuito (4 OCPUs e 24 GB ARM, 200 GB de disco) é da conta inteira. Se o teste de 30 dias acabou, a conta
segue com o Always Free.

**Recomendado:** depois de criada, passe a conta para **Pay As You Go** (*Billing → Upgrade and Manage Payment*).
Os recursos Always Free continuam gratuitos, e a Oracle deixa de recolher VMs gratuitas paradas (numa conta só
gratuita, uma VM com uso muito baixo por 7 dias pode ser recolhida). Para não ter surpresa, crie um alerta:
*Billing → Budgets → Create Budget*, valor **R$ 1** (ou US$ 1), alerta em 1% do valor, para o seu e-mail.

## 2. Chave SSH (no seu computador, PowerShell)

A senha (*passphrase*) pode ficar em branco (Enter duas vezes): a chave só serve até o passo 5, quando a porta 22
pública é fechada.

```powershell
New-Item -ItemType Directory -Force $HOME\.ssh   # o ssh-keygen não cria a pasta
ssh-keygen -t ed25519 -f $HOME\.ssh\oci_wallet -C "wallet-dev"
Get-Content $HOME\.ssh\oci_wallet.pub | Set-Clipboard   # a chave pública vai para a área de transferência
```

## 3. Criar a VM

**Primeiro, a rede**, pelo assistente (o formulário da VM às vezes escolhe uma sub-rede privada de uma rede que já
existia, e aí não deixa marcar o IP público): *Menu → Networking → Virtual cloud networks → Start VCN Wizard →
**Create VCN with Internet Connectivity***, nome `wallet-vcn`, o resto como está. Ele cria a rede, uma **sub-rede
pública** e a saída para a internet.

**Depois, a VM:** *Menu → Compute → Instances → Create instance*:

| Campo | Valor |
|---|---|
| Name | `wallet-dev` |
| Image | **Canonical Ubuntu 24.04** (com o shape abaixo, a Oracle usa a versão ARM) |
| Shape | *Change shape → Ampere → **VM.Standard.A1.Flex***, **4 OCPUs** e **24 GB** |
| Networking | *Select existing virtual cloud network* → `wallet-vcn`; *Select existing subnet* → **`public subnet-wallet-vcn`**; marque **Automatically assign public IPv4 address** |
| SSH keys | fica no fim da etapa Networking (*Add SSH keys*): *Paste public keys* e o conteúdo de `oci_wallet.pub` |
| Boot volume | *Specify a custom boot volume size*: **200 GB** |

Se aparecer:

- **"You must select a public subnet to assign a public IPv4 address"**: a sub-rede escolhida é a privada. Escolha a
  `public subnet-wallet-vcn`.
- **"Authorization failed or requested resource not found"**: a rede e a VM estão em compartimentos (ou regiões)
  diferentes, ou o OCID colado é da rede (`ocid1.vcn.…`) e não da sub-rede (`ocid1.subnet.…`). Na lista de redes,
  *Change compartment* mostra as de outro compartimento.
- **"Out of capacity for shape VM.Standard.A1.Flex"**: falta momentânea de máquinas ARM na região, não erro de
  configuração. Nesta ordem:
  1. **Pay As You Go** (passo 1): na prática, contas pagas conseguem capacidade com mais facilidade, e o Always Free
     continua sem custo.
  2. **Comece menor**: 2 OCPUs e 12 GB já rodam a stack. Depois, *Edit → Shape* sobe para 4 OCPUs e 24 GB na mesma
     instância, sem recriar nada.
  3. **Deixe o Cloud Shell tentar a cada minuto.** Abra o Cloud Shell (o ícone `>_` no topo do painel), envie o
     `scripts/oci-criar-vm.sh` do repositório (menu da engrenagem → *Upload*) e rode, com o OCID da sub-rede pública
     (*Networking → Virtual cloud networks → wallet-vcn → Subnets → public subnet-wallet-vcn*) e a sua chave:

     ```bash
     bash oci-criar-vm.sh ocid1.subnet.oc1.sa-saopaulo-1.aaaa... 'ssh-ed25519 AAAA... wallet-dev'
     ```

     O script descobre o compartimento, o *availability domain* e a imagem, e tenta até criar. O Cloud Shell fecha
     depois de uns 20 minutos sem interação: mexa nele de vez em quando, ou rode de novo.
  4. **Ou deixe o seu Windows tentando o dia todo**, sem o limite do Cloud Shell:
     1. instale e configure o OCI CLI (PowerShell normal, não como administrador; abra um PowerShell novo depois
        da instalação, para o PATH valer):

        ```powershell
        winget install Oracle.OCI-CLI
        oci setup config   # OCID do usuário (perfil → My profile), OCID da conta (tenancy), região sa-saopaulo-1;
                           # gere a chave de API e deixe a passphrase vazia
        Get-Content $HOME\.oci\oci_api_key_public.pem | Set-Clipboard
        ```

        e cole a chave em *My profile → API keys → Add API key → Paste a public key*. Teste com
        `oci iam region list --output table`;
     2. rode o laço numa janela própria, minimizada:

        ```powershell
        Start-Process powershell -WindowStyle Minimized -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','scripts\oci-criar-vm.ps1','-Subnet','ocid1.subnet.oc1.sa-saopaulo-1.aaaa...'
        Get-Content $HOME\oci-criar-vm.log -Tail 5   # o andamento
        ```

     A cada minuto, `scripts/oci-criar-vm.ps1` tenta 2 OCPUs / 12 GB e, sem vaga, 1 OCPU / 6 GB (o que vier primeiro;
     depois *Edit → Shape* aumenta). Enquanto roda, o Windows não suspende (mas fechar a tampa do notebook
     interrompe); ao criar, abre um aviso. Fechar a janela minimizada para o laço; um erro que não seja de capacidade
     para sozinho e vai inteiro para o log. A chave SSH usada é `$HOME\.ssh\oci_wallet.pub` (`-PublicKey` muda).

Anote o **Public IP address** da VM.

## 4. Primeiro acesso e pacotes

```powershell
ssh -i $HOME\.ssh\oci_wallet ubuntu@<IP_PUBLICO>
```

Na VM:

```bash
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get install -y git curl tmux unzip netfilter-persistent

# Docker (com o compose)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu

# Logs dos containers com tamanho limitado: sem isso, os logs enchem o disco em semanas.
echo '{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" } }' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker

# Node 22 (o console roda com ng serve)
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs

# JDK 25 e Maven, para rodar builds e testes fora do Docker (o Claude Code e o VS Code usam)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem
sdk install maven

exit   # saia e entre de novo, para o grupo docker valer
```

## 5. Tailscale: o acesso sem portas abertas

Na VM:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --ssh --hostname wallet-dev   # abre um link: entre com a mesma conta do seu PC e do celular

# As imagens Ubuntu da Oracle recusam tudo que chega, menos o SSH. Libera só o que vem pela Tailscale:
sudo iptables -I INPUT -i tailscale0 -j ACCEPT
sudo netfilter-persistent save
```

No seu computador, com a Tailscale instalada e logada na mesma conta:

```powershell
ssh ubuntu@wallet-dev    # pela Tailscale: sem IP público e sem chave (o --ssh cuida da autenticação)
```

Funcionou? **Feche o SSH público:** *Networking → Virtual cloud networks → (a VCN) → Security Lists → Default Security
List* e apague a regra de entrada `0.0.0.0/0` TCP 22. A partir daí a VM não tem nenhuma porta aberta na internet.
Se a Tailscale parar algum dia, o acesso de emergência é o *Console connection* da instância, no painel da Oracle.

## 6. O código

O repositório é privado: dê à VM acesso ao GitHub com o `gh`.

```bash
sudo apt-get install -y gh
gh auth login            # GitHub.com → HTTPS → Login with a web browser (abre um código para colar)
gh repo clone erikmatioli/wallet ~/wallet
cd ~/wallet
git config user.name  "Erik Matioli"
git config user.email "<seu e-mail do GitHub>"
```

## 7. Subir a stack

```bash
cd ~/wallet
scripts/dev-stack.sh up          # a primeira vez compila todas as imagens: 15 a 25 minutos
scripts/dev-stack.sh status      # containers e o health de cada serviço
```

O console roda à parte, numa sessão do `tmux`, para continuar depois que você fechar o SSH:

```bash
tmux new -s console
scripts/dev-stack.sh console     # Ctrl+B e depois D para sair deixando rodando; "tmux attach -t console" volta
```

| Comando | O que faz |
|---|---|
| `scripts/dev-stack.sh up` | constrói o que mudou e sobe tudo na ordem, esperando cada serviço responder |
| `scripts/dev-stack.sh up --fast` | sobe sem reconstruir |
| `scripts/dev-stack.sh down` | para tudo (os dados ficam) |
| `scripts/dev-stack.sh status` | containers e health |

Os dados começam vazios: o core cria `demo-tenant` e `segundo-tenant` na primeira subida.

## 8. Acessar

Do seu computador ou do celular, com a Tailscale ligada (`wallet-dev` é o nome da VM na Tailscale; se o nome não
resolver, use o IP `100.x.y.z` que aparece no app da Tailscale):

| O quê | Endereço |
|---|---|
| Console | http://wallet-dev:4200 |
| M-Wall (demo-tenant / segundo-tenant) | http://wallet-dev:8086 / http://wallet-dev:8087 |
| Mailpit (os códigos de login) | http://wallet-dev:8025 |
| wallet-core (Swagger) | http://wallet-dev:8080/swagger-ui.html |
| Grafana / Jaeger | http://wallet-dev:3000 / http://wallet-dev:16686 |

Se o console responder **"Invalid Host header"**, use o IP `100.x.y.z` no lugar do nome.

**M-Wall como app instalado (PWA):** o "Adicionar à tela inicial" exige HTTPS. A Tailscale dá um certificado válido
só para os seus aparelhos: no painel da Tailscale, *DNS → HTTPS Certificates → Enable*; na VM,
`sudo tailscale serve --bg 8086`; no celular, `https://wallet-dev.<sua-tailnet>.ts.net`.

## 9. Desenvolver na VM

**VS Code:** instale a extensão *Remote - SSH*, *Connect to Host → `ubuntu@wallet-dev`* e abra `~/wallet`. O editor
roda no seu PC; os terminais, o build e os testes, na VM.

**Claude Code** direto na VM:

```bash
curl -fsSL https://claude.ai/install.sh | bash
cd ~/wallet && claude
```

## 10. Cuidados para continuar gratuito

- **Uma VM A1 só**, com no máximo 4 OCPUs e 24 GB, e no máximo **200 GB** de disco somando todos os volumes.
- Não crie *Load Balancer*, *Database*, *NAT Gateway* nem IP público reservado além do gratuito: a Tailscale torna
  tudo isso desnecessário.
- O alerta de orçamento de R$ 1 avisa no primeiro centavo cobrado.
- **Backup dos bancos** (o disco da VM não tem cópia):

  ```bash
  mkdir -p ~/backups
  for db in wallet app app_segundo pix scheduler otp; do
    docker exec wallet-core-postgres-1 pg_dump -U wallet_owner -d $db -Fc > ~/backups/$db-$(date +%F).dump
  done
  ```
