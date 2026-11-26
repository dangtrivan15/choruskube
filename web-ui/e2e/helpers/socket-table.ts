/**
 * socket-table.ts — pure parser for Linux's `/proc/net/{tcp,tcp6,udp,udp6}` text, the kernel's own
 * socket table. `helpers/docker.ts#listenersOf` reads these files out of a container via `docker
 * exec`; this module only decodes the text, so it has no Docker dependency and is unit-testable on
 * its own (see `socket-table.test.ts`).
 *
 * Each line's `local_address` field is `<hex address>:<hex port>`. An IPv4 address is 4 bytes
 * written as 8 hex chars in reverse (little-endian) byte order; an IPv6 address is 16 bytes written
 * as 4 consecutive 32-bit words, each word independently byte-reversed. The `st` field is the
 * socket's state, also hex: TCP's LISTEN state is `0A`; UDP has no listen/connect state machine, so
 * every bound UDP socket is a listener regardless of what `st` says.
 */

export interface Listener {
  proto: "tcp" | "tcp6" | "udp" | "udp6";
  address: string;
  port: number;
}

const TCP_LISTEN_STATE = "0A";

function decodeIPv4(hex: string): string {
  const bytes = [hex.slice(0, 2), hex.slice(2, 4), hex.slice(4, 6), hex.slice(6, 8)].map((h) => parseInt(h, 16));
  return bytes.reverse().join(".");
}

/** Reverses one 32-bit word's 4 bytes back to network order, the inverse of how the kernel wrote them. */
function wordBytes(word: string): number[] {
  return [word.slice(6, 8), word.slice(4, 6), word.slice(2, 4), word.slice(0, 2)].map((h) => parseInt(h, 16));
}

/**
 * Formats 16 raw address bytes as an IPv6 literal: the IPv4-mapped form (`::ffff:a.b.c.d`) when the
 * first 10 bytes are zero and the next 2 are `ffff`, otherwise standard `::`-compression of the
 * longest run of zero 16-bit groups (ties keep the first, matching every OS's own formatter).
 */
function formatIPv6(bytes: number[]): string {
  const words: number[] = [];
  for (let i = 0; i < 16; i += 2) words.push((bytes[i] << 8) | bytes[i + 1]);

  if (words.slice(0, 5).every((w) => w === 0) && words[5] === 0xffff) {
    return `::ffff:${bytes[12]}.${bytes[13]}.${bytes[14]}.${bytes[15]}`;
  }

  let bestStart = -1;
  let bestLen = 0;
  let curStart = -1;
  let curLen = 0;
  for (let i = 0; i < words.length; i++) {
    if (words[i] === 0) {
      if (curStart === -1) curStart = i;
      curLen++;
      if (curLen > bestLen) {
        bestLen = curLen;
        bestStart = curStart;
      }
    } else {
      curStart = -1;
      curLen = 0;
    }
  }

  if (bestLen < 2) {
    return words.map((w) => w.toString(16)).join(":");
  }
  const left = words.slice(0, bestStart).map((w) => w.toString(16));
  const right = words.slice(bestStart + bestLen).map((w) => w.toString(16));
  return `${left.join(":")}::${right.join(":")}`;
}

function decodeIPv6(hex: string): string {
  const bytes: number[] = [];
  for (let w = 0; w < 4; w++) {
    bytes.push(...wordBytes(hex.slice(w * 8, w * 8 + 8)));
  }
  return formatIPv6(bytes);
}

function decodeAddress(hex: string): string {
  return hex.length === 8 ? decodeIPv4(hex) : decodeIPv6(hex);
}

function parseOneFile(proto: Listener["proto"], text: string): Listener[] {
  const listeners: Listener[] = [];
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (trimmed.length === 0 || trimmed.startsWith("sl")) continue; // header or blank

    const fields = trimmed.split(/\s+/);
    const localAddress = fields[1];
    const state = fields[3];
    if (!localAddress || !localAddress.includes(":")) continue;

    const isTcp = proto === "tcp" || proto === "tcp6";
    if (isTcp && state !== TCP_LISTEN_STATE) continue;

    const sep = localAddress.lastIndexOf(":");
    const addressHex = localAddress.slice(0, sep);
    const portHex = localAddress.slice(sep + 1);
    listeners.push({ proto, address: decodeAddress(addressHex), port: parseInt(portHex, 16) });
  }
  return listeners;
}

/** Every listening TCP socket (state `0A`) and every bound UDP socket, across all four files. */
export function parseProcNetListeners(files: Record<Listener["proto"], string>): Listener[] {
  return (Object.keys(files) as Listener["proto"][]).flatMap((proto) => parseOneFile(proto, files[proto]));
}

export function isLoopback(l: Listener): boolean {
  return /^127\./.test(l.address) || l.address === "::1" || /^::ffff:127\./.test(l.address);
}

export function offLoopback(ls: Listener[]): Listener[] {
  return ls.filter((l) => !isLoopback(l));
}
