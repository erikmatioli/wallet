#!/bin/bash
# Creates the local Pix bus on LocalStack startup (mounted into /etc/localstack/init/ready.d).
# Idempotent: create-topic/create-queue return the existing resource when it already exists.
set -euo pipefail

REGION=us-east-1
ACCOUNT=000000000000

topic() { awslocal sns create-topic --name "$1" --region $REGION --query TopicArn --output text; }

queue() {  # queue NAME [DLQ_NAME]
  local attrs='{"VisibilityTimeout":"15"}'
  if [ -n "${2:-}" ]; then
    local dlq_arn="arn:aws:sqs:$REGION:$ACCOUNT:$2"
    awslocal sqs create-queue --queue-name "$2" --region $REGION > /dev/null
    attrs="{\"VisibilityTimeout\":\"15\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}"
  fi
  awslocal sqs create-queue --queue-name "$1" --attributes "$attrs" --region $REGION > /dev/null
  echo "arn:aws:sqs:$REGION:$ACCOUNT:$1"
}

subscribe() {  # subscribe TOPIC_ARN QUEUE_ARN
  awslocal sns subscribe --topic-arn "$1" --protocol sqs --notification-endpoint "$2" \
    --attributes RawMessageDelivery=true --region $REGION > /dev/null
}

SPI_TO_PSP=$(topic spi-to-psp)                  # SPI -> Pix service
PSP_TO_SPI=$(topic psp-to-spi)                  # Pix service -> SPI
EVENTS=$(topic pix-payment-events)              # Pix service -> anyone interested

subscribe "$SPI_TO_PSP" "$(queue wallet-pix-spi-inbound wallet-pix-spi-inbound-dlq)"
subscribe "$PSP_TO_SPI" "$(queue spi-simulator-inbound)"
subscribe "$EVENTS"     "$(queue pix-events-dev)"
# wallet-scheduler: results of the Pix it sends (its ADR-001). Own queue and DLQ on the same topic.
subscribe "$EVENTS"     "$(queue wallet-scheduler-pix-events wallet-scheduler-pix-events-dlq)"

echo "Pix bus ready: topics spi-to-psp, psp-to-spi, pix-payment-events"
