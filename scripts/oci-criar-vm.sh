#!/usr/bin/env bash
# Cria a VM de desenvolvimento (Ampere A1, Ubuntu 24.04) na Oracle Cloud, tentando a cada minuto enquanto a região
# estiver sem capacidade ("Out of capacity"). Roda no Cloud Shell da Oracle, que já vem com o `oci` configurado.
# Guia: docs/ambiente-remoto-oracle.md, passo 3.
#
#   bash oci-criar-vm.sh <OCID da sub-rede pública> '<chave pública ssh-ed25519 ...>' [ocpus] [memória GB]
#
# Padrão: 2 OCPUs e 12 GB, que conseguem vaga com mais facilidade; depois, Edit → Shape sobe para 4 e 24 GB.
# Ctrl+C interrompe.

SUBNET=${1:?informe o OCID da sub-rede pública}
KEY=${2:?informe a chave pública (a linha inteira ssh-ed25519 ...)}
OCPUS=${3:-2}
MEMORY=${4:-12}

printf '%s\n' "$KEY" > ~/oci_wallet.pub

# A VM vai no compartimento da sub-rede: misturar compartimentos dá "Authorization failed or requested resource not
# found".
COMPARTMENT=$(oci network subnet get --subnet-id "$SUBNET" --query 'data."compartment-id"' --raw-output)
AD=$(oci iam availability-domain list --compartment-id "$COMPARTMENT" --query 'data[0].name' --raw-output)
IMAGE=$(oci compute image list --compartment-id "$COMPARTMENT" --operating-system "Canonical Ubuntu" \
  --operating-system-version "24.04" --shape VM.Standard.A1.Flex --sort-by TIMECREATED \
  --query 'data[0].id' --raw-output)

echo "chave:       $(cat ~/oci_wallet.pub)"
echo "compartment: $COMPARTMENT"
echo "AD:          $AD"
echo "imagem:      $IMAGE"
echo "shape:       VM.Standard.A1.Flex, $OCPUS OCPUs, $MEMORY GB"
if [ -z "$COMPARTMENT" ] || [ -z "$AD" ] || [ -z "$IMAGE" ]; then
  echo "Algum valor veio vazio: confira o OCID da sub-rede e a região do Cloud Shell."
  exit 1
fi

for i in $(seq 1 600); do
  echo "tentativa $i - $(date +%H:%M)"
  if oci compute instance launch --availability-domain "$AD" --compartment-id "$COMPARTMENT" \
      --shape VM.Standard.A1.Flex --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEMORY}" \
      --image-id "$IMAGE" --subnet-id "$SUBNET" --assign-public-ip true \
      --boot-volume-size-in-gbs 200 --display-name wallet-dev \
      --ssh-authorized-keys-file ~/oci_wallet.pub >/tmp/launch.json 2>/tmp/launch.err; then
    echo "CRIADA! Veja o IP público em Compute > Instances > wallet-dev."
    break
  fi
  msg=$(grep -o '"message": "[^"]*"' /tmp/launch.err | head -1)
  echo "  $msg"
  case "$msg" in
    *"Out of capacity"*|*"Out of host capacity"*|*"TooManyRequests"*) sleep 60 ;;
    *) echo "Erro diferente de falta de capacidade:"; cat /tmp/launch.err; exit 1 ;;
  esac
done
