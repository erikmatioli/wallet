import { documentKind, formatDocument } from './document';

describe('formatDocument', () => {
  it('formats a CPF and a CNPJ, the alphanumeric one too', () => {
    expect(formatDocument('52998224725')).toBe('529.982.247-25');
    expect(formatDocument('11222333000181')).toBe('11.222.333/0001-81');
    expect(formatDocument('12abc34501de35')).toBe('12.ABC.345/01DE-35');
  });

  it('falls back to the masked document when the full one did not come', () => {
    expect(formatDocument(null, '***4725')).toBe('***4725');
    expect(formatDocument(undefined)).toBe('');
  });

  it('tells a CPF from a CNPJ', () => {
    expect(documentKind('52998224725')).toBe('CPF');
    expect(documentKind('11222333000181')).toBe('CNPJ');
    expect(documentKind(null)).toBeNull();
  });
});
