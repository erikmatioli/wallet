import { dayLabelBr, daysBefore, lastClosedDay } from './treasury';

describe('treasury days', () => {
  it('the last closed day is yesterday in Brasília', () => {
    // 10/10 at 09:00 in Brasília
    expect(lastClosedDay(new Date('2026-10-10T12:00:00Z'))).toBe('2026-10-09');
    // 10/10 at 01:30 UTC is still 09/10 at 22:30 in Brasília: the 8th is the last closed day
    expect(lastClosedDay(new Date('2026-10-10T01:30:00Z'))).toBe('2026-10-08');
  });

  it('waits the minute of grace after midnight', () => {
    // 00:00:30 in Brasília: the 9th may still have entries committing
    expect(lastClosedDay(new Date('2026-10-10T03:00:30Z'))).toBe('2026-10-08');
    expect(lastClosedDay(new Date('2026-10-10T03:02:00Z'))).toBe('2026-10-09');
  });

  it('counts days back across months', () => {
    expect(daysBefore('2026-10-09', 13)).toBe('2026-09-26');
  });

  it('writes the day without moving it to another timezone', () => {
    expect(dayLabelBr('2026-10-10')).toBe('10/10/2026');
  });
});
