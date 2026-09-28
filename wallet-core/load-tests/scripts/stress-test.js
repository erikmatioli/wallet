import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import encoding from 'k6/encoding';

export const options = {
    // Cenário de Estresse / Carga Progressiva
    stages: [
        { duration: '30s', target: 10 },  // Aquecimento: sobe para 10 usuários em 30s
        { duration: '1m', target: 50 },   // Carga sustentada: 50 usuários por 1 minuto
        { duration: '30s', target: 100 }, // Pico de estresse: sobe para 100 usuários
        { duration: '1m', target: 100 },  // Mantém 100 usuários por 1 minuto
        { duration: '30s', target: 0 },   // Desaquecimento / Encerramento
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'], // 95% das requisições devem responder em menos de 500ms
        http_req_failed: ['rate<0.01'],   // Taxa de erro deve ser menor que 1%
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://wallet-core:8080';

// Credenciais de teste configuradas no seu ambiente de dev
const CLIENT_ID = 'demo-tenant';
const CLIENT_SECRET = 'demo-secret-change-me-please';

export function setup() {
    // Etapa de Setup: Obtém o Token JWT via Basic Auth antes de iniciar o teste de carga
    const credentials = encoding.b64encode(`${CLIENT_ID}:${CLIENT_SECRET}`);
    const res = http.post(`${BASE_URL}/v1/auth/token`, null, {
        headers: {
            'Authorization': `Basic ${credentials}`,
        },
    });

    check(res, { 'token obtido com sucesso': (r) => r.status === 200 });
    return { token: res.json('access_token') };
}

export default function (data) {
    const authToken = data.token;
    const headers = {
        'Authorization': `Bearer ${authToken}`,
        'Content-Type': 'application/json',
    };

    let accountId;

    // 1. Grupo de Onboard (Criação de Cliente e Conta)
    group('01. Onboard Customer & Account', () => {
        const payload = JSON.stringify({
            name: `Load Test User ${uuidv4().substring(0, 8)}`,
            taxId: Math.floor(10000000000 + Math.random() * 90000000000).toString(),
            externalRef: uuidv4(),
        });

        const res = http.post(`${BASE_URL}/v1/customers`, payload, { headers });

        // ADICIONE ESTE BLOCO PARA VER O MOTIVO DO 400 NO TERMINAL:
        if (res.status !== 201) {
            console.log(`[ERRO ${res.status}] Payload enviado: ${payload} | Resposta da API: ${res.body}`);
        }

        check(res, {
            'status é 201': (r) => r.status === 201,
        });

        if (res.status === 201) {
            const body = res.json();
            accountId = body.account.id;
        }
    });

    // Se conseguiu criar a conta, executa as operações financeiras
    if (accountId) {
        // 2. Depósito (Testando Idempotência)
        group('02. Deposit with Idempotency', () => {
            const idempotencyKey = uuidv4();
            const payload = JSON.stringify({
                amount: 500.00,
                description: 'Carga de saldo inicial stress test',
            });

            const res = http.post(`${BASE_URL}/v1/accounts/${accountId}/deposits`, payload, {
                headers: Object.assign({}, headers, { 'Idempotency-Key': idempotencyKey }),
            });

            check(res, {
                'depósito aceito (201 ou 200)': (r) => r.status === 201 || r.status === 200,
            });
        });

        // 3. Consulta de Saldo
        group('03. Get Balance', () => {
            const res = http.get(`${BASE_URL}/v1/accounts/${accountId}/balance`, { headers });
            check(res, {
                'saldo consultado com sucesso': (r) => r.status === 200,
            });
        });

        // 4. Consulta de Extrato
        group('04. Get Statement', () => {
            const res = http.get(`${BASE_URL}/v1/accounts/${accountId}/statement?limit=10`, { headers });
            check(res, {
                'extrato obtido com sucesso': (r) => r.status === 200,
            });
        });
    }

    // Pausa randômica entre 1s e 3s para simular comportamento real de usuário
    sleep(Math.random() * 2 + 1);
}