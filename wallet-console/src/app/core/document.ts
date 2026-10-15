/**
 * The holder's CPF/CNPJ as the operator reads it: "529.982.247-25", "12.ABC.345/01DE-35" (the alphanumeric CNPJ
 * too). wallet-core sends the full document only to a token with customers:document; without it, or for a value of
 * an unknown shape, the masked one is shown as it came.
 */
export function formatDocument(document: string | null | undefined, masked = ''): string {
  if (!document) return masked;
  const d = document.toUpperCase();
  if (/^\d{11}$/.test(d)) return `${d.slice(0, 3)}.${d.slice(3, 6)}.${d.slice(6, 9)}-${d.slice(9)}`;
  if (/^[0-9A-Z]{12}\d{2}$/.test(d)) {
    return `${d.slice(0, 2)}.${d.slice(2, 5)}.${d.slice(5, 8)}/${d.slice(8, 12)}-${d.slice(12)}`;
  }
  return d;
}

/** "CPF" or "CNPJ", by the shape of the full document; null without it. */
export function documentKind(document: string | null | undefined): 'CPF' | 'CNPJ' | null {
  if (!document) return null;
  return document.length === 11 ? 'CPF' : 'CNPJ';
}
