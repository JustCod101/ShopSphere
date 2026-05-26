import { v4 as uuid } from 'uuid';

const NS = 'shopsphere.reqid';
const TTL_MS = 5 * 60 * 1000;

interface Entry {
  id: string;
  expiresAt: number;
}

type EntryMap = Record<string, Entry>;

function read(): EntryMap {
  if (typeof sessionStorage === 'undefined') return {};
  try {
    return JSON.parse(sessionStorage.getItem(NS) || '{}') as EntryMap;
  } catch {
    return {};
  }
}

function write(m: EntryMap): void {
  if (typeof sessionStorage === 'undefined') return;
  sessionStorage.setItem(NS, JSON.stringify(m));
}

export function getOrCreateRequestId(key: string): string {
  const now = Date.now();
  const map = read();
  const hit = map[key];
  if (hit && hit.expiresAt > now) return hit.id;

  const fresh: Entry = { id: uuid(), expiresAt: now + TTL_MS };
  map[key] = fresh;

  for (const k of Object.keys(map)) {
    const v = map[k];
    if (v && v.expiresAt <= now) delete map[k];
  }

  write(map);
  return fresh.id;
}

export function resetRequestId(key: string): void {
  const map = read();
  delete map[key];
  write(map);
}
