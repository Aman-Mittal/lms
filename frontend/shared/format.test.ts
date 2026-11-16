import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  bindFormatPreferences, columnKind, formatDate, isDateColumn, formatDateTime, formatIfDateText, formatMoney, formatQuantity, formatRelative,
  formatWeight, labelFor, toneFor,
} from './format';

test('money groups the Indian way and defaults to INR', () => {
  assert.equal(formatMoney(12500.5), '₹12,500.50');
  assert.equal(formatMoney('1234567.891'), '₹12,34,567.89');
  assert.equal(formatMoney(0), '₹0.00');
  assert.equal(formatMoney(-1500), '-₹1,500.00');
  assert.equal(formatMoney(20000, 'USD'), '$20,000.00');
  assert.equal(formatMoney(5, 'XYZ1'), 'XYZ1 5.00');
});

test('money copes with null, empty and text', () => {
  assert.equal(formatMoney(null), '');
  assert.equal(formatMoney(undefined), '');
  assert.equal(formatMoney(''), '');
  assert.equal(formatMoney('n/a'), 'n/a');
  assert.equal(formatMoney(12, null), '₹12.00');
});

test('quantities and weights carry units', () => {
  assert.equal(formatQuantity(1250, 'pcs'), '1,250 pcs');
  assert.equal(formatQuantity(24), '24');
  assert.equal(formatQuantity('1999999.5', 'kg'), '19,99,999.5 kg');
  assert.equal(formatWeight(2000000), '20,00,000 kg');
  assert.equal(formatWeight(1.5, 't'), '1.5 t');
  assert.equal(formatQuantity(null, 'kg'), '');
});

test('labels turn enums into words', () => {
  assert.equal(labelFor('PENDING_APPROVAL'), 'Pending approval');
  assert.equal(labelFor('OTP_ISSUED'), 'OTP issued');
  assert.equal(labelFor('APPROVED'), 'Approved');
  assert.equal(labelFor('Retired'), 'Retired');
  assert.equal(labelFor(''), '');
  assert.equal(labelFor(null), '');
});

test('tones', () => {
  assert.equal(toneFor('APPROVED'), 'success');
  assert.equal(toneFor('PENDING_APPROVAL'), 'warning');
  assert.equal(toneFor('REJECTED'), 'danger');
  assert.equal(toneFor('IN_TRANSIT'), 'info');
  assert.equal(toneFor('DRAFT'), 'neutral');
  assert.equal(toneFor('SOMETHING_FAILED'), 'danger');
  assert.equal(toneFor(undefined), 'neutral');
});

test('dates honour the preferences', () => {
  const prefs = { dateFormat: 'DD/MM/YYYY', timeZone: 'Asia/Kolkata', locale: 'en-IN' };
  assert.equal(formatDateTime('2026-11-12T09:17:42.123Z', prefs), '12/11/2026 14:47');
  assert.equal(formatDate('2026-11-12T20:00:00Z', prefs), '13/11/2026');
  assert.equal(formatDate('2026-11-12', prefs), '12/11/2026');
  assert.equal(formatDateTime('2026-11-12T09:17:42.123Z'), '2026-11-12 09:17');
  bindFormatPreferences(() => ({ dateFormat: 'DD MMM YYYY', timeZone: 'UTC', locale: 'en-GB' }));
  assert.equal(formatDate(new Date('2026-11-12T00:00:00Z')), '12 Nov 2026');
  assert.equal(formatDateTime(null), '');
  assert.equal(formatDate('garbage'), '');
});

test('timestamps in text are recognised, other text is not', () => {
  assert.equal(formatIfDateText('2026-11-12T09:17:42.123Z', { dateFormat: 'YYYY-MM-DD', timeZone: 'UTC', locale: 'en-GB' }), '2026-11-12 09:17');
  assert.equal(formatIfDateText('PO-2026-11-12T'), null);
  assert.equal(formatIfDateText('2026-11-12', { dateFormat: 'DD/MM/YYYY', timeZone: 'UTC', locale: 'en-GB' }), '12/11/2026');
  assert.equal(formatIfDateText('2026-13-45x'), null);
});

test('relative time', () => {
  const now = new Date('2026-11-12T12:00:00Z');
  assert.equal(formatRelative('2026-11-12T09:00:00Z', now), '3 hours ago');
  assert.equal(formatRelative('2026-11-14T12:00:00Z', now), 'in 2 days');
  assert.equal(formatRelative('', now), '');
});

test('generic columns are classified by field name and value', () => {
  assert.equal(columnKind('status', 'APPROVED'), 'status');
  assert.equal(columnKind('status', 'Active'), 'text');
  assert.equal(columnKind('totalAmount', '12500.5'), 'money');
  assert.equal(columnKind('totalAmount', ''), 'text');
  assert.equal(columnKind('invoiceNo', '1234'), 'text');
  assert.equal(columnKind('totalDeadWeightKg', '2000'), 'weight');
  assert.equal(columnKind('quantity', '24'), 'quantity');
});

test('date columns are recognised by field name', () => {
  assert.equal(isDateColumn('dueDate'), true);
  assert.equal(isDateColumn('issuedAt'), true);
  assert.equal(isDateColumn('status'), false);
});
