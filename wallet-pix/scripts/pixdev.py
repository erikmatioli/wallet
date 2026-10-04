#!/usr/bin/env python3
"""Developer tool for the local Pix stack (standard library only).

  python scripts/pixdev.py send --from-account <uuid> --from-taxid <cpf> --to-ispb 87654321
      --to-branch 0001 --to-account 001000029 --to-taxid 52998224725 --amount 10.00
      Calls the Pix service's send API (POST /v1/pix/payments) with a wallet-core token restricted
      to pix:send. The service checks policies and the payer, debits, then sends the pacs.008.

  python scripts/pixdev.py status --e2e <EndToEndId> [--ispb 12345678]
      Status of an outgoing Pix (GET /v1/pix/payments/{e2e}).

  python scripts/pixdev.py incoming --ispb 12345678 --branch 0001 --account 001000029
      --taxid 52998224725 --name "Maria Silva" --amount 25.00
      A Pix arriving from the simulator's external PSP (99999999).

  python scripts/pixdev.py return --e2e <EndToEndId> --amount 5.00
      The external PSP returns (part of) a Pix we sent it (pacs.004).

  python scripts/pixdev.py messages | events
      What crossed the simulated SPI / the PixEvents published (drains pix-events-dev).
"""
import argparse
import base64
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

WALLET_CORE = "http://localhost:8080"
PIX_SERVICE = "http://localhost:8081"
SIMULATOR = "http://localhost:8090"
LOCALSTACK = "http://localhost:4566"
EVENTS_QUEUE = f"{LOCALSTACK}/000000000000/pix-events-dev"
TENANTS = {  # wallet-core "dev" profile tenants (DevDataSeeder)
    "12345678": ("demo-tenant", "demo-secret-change-me-please"),
    "87654321": ("segundo-tenant", "segundo-tenant-secret-please"),
}


def http(method, url, body=None, headers=None, form=None):
    data = None
    headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
            ctype = r.headers.get("Content-Type", "")
            return json.loads(raw) if raw and "json" in ctype else raw.decode()
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url} -> {e.code}: {e.read().decode()}")


def token(ispb, scope=None):
    client, secret = TENANTS[ispb]
    basic = base64.b64encode(f"{client}:{secret}".encode()).decode()
    url = f"{WALLET_CORE}/v1/auth/token" + (f"?scope={urllib.parse.quote(scope)}" if scope else "")
    return http("POST", url, headers={"Authorization": f"Basic {basic}"})["access_token"]


def send(a):
    request_id = a.request_id or str(uuid.uuid4())
    result = http("POST", f"{PIX_SERVICE}/v1/pix/payments", headers={
        "Authorization": f"Bearer {token(a.from_ispb, 'pix:send')}", "Idempotency-Key": request_id}, body={
        "payerAccountId": a.from_account, "payerTaxId": a.from_taxid,
        "payee": {"ispb": a.to_ispb, "branch": a.to_branch, "accountNumber": a.to_account,
                  "taxId": a.to_taxid, "name": a.to_name},
        "amount": a.amount, "description": a.description})
    print(json.dumps({"requestId": request_id, **result}, indent=2))


def status(a):
    print(json.dumps(http("GET", f"{PIX_SERVICE}/v1/pix/payments/{a.e2e}", headers={
        "Authorization": f"Bearer {token(a.ispb, 'pix:send')}"}), indent=2))


def incoming(a):
    print(json.dumps(http("POST", f"{SIMULATOR}/simulate/incoming", body={
        "payeeIspb": a.ispb, "branch": a.branch, "accountNumber": a.account, "accountType": a.account_type,
        "taxId": a.taxid, "name": a.name, "amount": a.amount, "payerName": a.payer_name,
        "description": a.description}), indent=2))


def return_pix(a):
    print(json.dumps(http("POST", f"{SIMULATOR}/simulate/return", body={
        "endToEndId": a.e2e, "amount": a.amount, "reason": a.reason}), indent=2))


def messages(_):
    for m in http("GET", f"{SIMULATOR}/simulate/messages")[:30]:
        print(f"{m['at'][11:19]} {m['direction']:>3} {m['msgType']} {m['from']} -> {m['to']} "
              f"{m['endToEndId']} {m.get('status') or ''} {m.get('reason') or ''}")


def events(_):
    raw = http("POST", LOCALSTACK, form={"Action": "ReceiveMessage", "QueueUrl": EVENTS_QUEUE,
                                         "MaxNumberOfMessages": "10", "WaitTimeSeconds": "1"})
    bodies = raw.split("<Body>")[1:]
    if not bodies:
        print("(no new events)")
    for b in bodies:
        print(json.loads(b.split("</Body>")[0].replace("&quot;", '"').replace("&amp;", "&")))
    for handle in [r.split("</ReceiptHandle>")[0] for r in raw.split("<ReceiptHandle>")[1:]]:
        http("POST", LOCALSTACK, form={"Action": "DeleteMessage", "QueueUrl": EVENTS_QUEUE, "ReceiptHandle": handle})


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("send")
    s.add_argument("--from-ispb", default="12345678")
    s.add_argument("--from-account", required=True, help="wallet-core account id (uuid) of the payer")
    s.add_argument("--from-taxid", required=True, help="payer CPF/CNPJ, checked against the account holder")
    s.add_argument("--to-ispb", required=True)
    s.add_argument("--to-branch", default="0001")
    s.add_argument("--to-account", required=True, help="account number with check digit appended")
    s.add_argument("--to-taxid", required=True)
    s.add_argument("--to-name", default="Recebedor")
    s.add_argument("--amount", required=True)
    s.add_argument("--description", default="Pix de teste")
    s.add_argument("--request-id", help="Idempotency-Key; repeat it to see the replay")
    s.set_defaults(fn=send)

    st = sub.add_parser("status")
    st.add_argument("--e2e", required=True)
    st.add_argument("--ispb", default="12345678")
    st.set_defaults(fn=status)

    i = sub.add_parser("incoming")
    i.add_argument("--ispb", default="12345678")
    i.add_argument("--branch", default="0001")
    i.add_argument("--account", required=True, help="account number with check digit appended")
    i.add_argument("--account-type", default="TRAN")
    i.add_argument("--taxid", required=True)
    i.add_argument("--name", default="Recebedor")
    i.add_argument("--amount", required=True)
    i.add_argument("--payer-name", default="Pagador Externo")
    i.add_argument("--description", default="Pix de teste")
    i.set_defaults(fn=incoming)

    r = sub.add_parser("return")
    r.add_argument("--e2e", required=True)
    r.add_argument("--amount", required=True)
    r.add_argument("--reason", default="MD06")
    r.set_defaults(fn=return_pix)

    sub.add_parser("messages").set_defaults(fn=messages)
    sub.add_parser("events").set_defaults(fn=events)

    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
