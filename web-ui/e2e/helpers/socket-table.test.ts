import { describe, it, expect } from "vitest";
import { parseProcNetListeners, isLoopback, offLoopback, type Listener } from "./socket-table";

const HEADER_TCP =
  "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode";
const HEADER_UDP = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops";

function tcpLine(local: string, state: string): string {
  return `   0: ${local} 00000000:0000 ${state} 00000000:00000000 00:00000000 00000000     0        0 8072 1 0000000000000000 100 0 0 10 0`;
}

function udpLine(local: string, state: string): string {
  return `   0: ${local} 00000000:0000 ${state} 00000000:00000000 00:00000000 00000000     0        0 8072 2 0000000000000000 0`;
}

function filesWith(proto: Listener["proto"], text: string): Record<Listener["proto"], string> {
  return {
    tcp: proto === "tcp" ? text : `${HEADER_TCP}\n`,
    tcp6: proto === "tcp6" ? text : `${HEADER_TCP}\n`,
    udp: proto === "udp" ? text : `${HEADER_UDP}\n`,
    udp6: proto === "udp6" ? text : `${HEADER_UDP}\n`,
  };
}

describe("parseProcNetListeners", () => {
  it("decodes an IPv4 0.0.0.0:2328 LISTEN row to port 9000 off loopback", () => {
    const text = `${HEADER_TCP}\n${tcpLine("00000000:2328", "0A")}\n`;
    const result = parseProcNetListeners(filesWith("tcp", text));
    expect(result).toEqual([{ proto: "tcp", address: "0.0.0.0", port: 9000 }]);
  });

  it("decodes 0100007F:208D to 127.0.0.1:8333 as loopback", () => {
    const text = `${HEADER_TCP}\n${tcpLine("0100007F:208D", "0A")}\n`;
    const [listener] = parseProcNetListeners(filesWith("tcp", text));
    expect(listener).toEqual({ proto: "tcp", address: "127.0.0.1", port: 8333 });
    expect(isLoopback(listener)).toBe(true);
  });

  it("decodes 0B00007F (127.0.0.11, Docker DNS) as loopback", () => {
    const text = `${HEADER_TCP}\n${tcpLine("0B00007F:0035", "0A")}\n`;
    const [listener] = parseProcNetListeners(filesWith("tcp", text));
    expect(listener.address).toBe("127.0.0.11");
    expect(isLoopback(listener)).toBe(true);
  });

  it("ignores TCP rows not in state 0A (for example 01 ESTABLISHED)", () => {
    const text = `${HEADER_TCP}\n${tcpLine("0100007F:1F90", "01")}\n`;
    expect(parseProcNetListeners(filesWith("tcp", text))).toEqual([]);
  });

  it("returns a bound UDP row whatever its state", () => {
    const text = `${HEADER_UDP}\n${udpLine("00000000:0035", "07")}\n${udpLine("0100007F:0044", "01")}\n`;
    const result = parseProcNetListeners(filesWith("udp", text));
    expect(result).toEqual([
      { proto: "udp", address: "0.0.0.0", port: 53 },
      { proto: "udp", address: "127.0.0.1", port: 68 },
    ]);
  });

  it("decodes the IPv6 ::1 and the :: wildcard", () => {
    const text = `${HEADER_TCP}\n${tcpLine(
      "00000000000000000000000001000000:0016",
      "0A",
    )}\n${tcpLine("00000000000000000000000000000000:1F90", "0A")}\n`;
    const result = parseProcNetListeners(filesWith("tcp6", text));
    expect(result).toEqual([
      { proto: "tcp6", address: "::1", port: 22 },
      { proto: "tcp6", address: "::", port: 8080 },
    ]);
  });

  it("decodes ::ffff:127.0.0.1", () => {
    const text = `${HEADER_TCP}\n${tcpLine("0000000000000000FFFF00000100007F:2328", "0A")}\n`;
    const [listener] = parseProcNetListeners(filesWith("tcp6", text));
    expect(listener.address).toBe("::ffff:127.0.0.1");
    expect(isLoopback(listener)).toBe(true);
  });

  it("skips header lines and blank input", () => {
    expect(parseProcNetListeners(filesWith("tcp", `${HEADER_TCP}\n\n   \n`))).toEqual([]);
    expect(parseProcNetListeners({ tcp: "", tcp6: "", udp: "", udp6: "" })).toEqual([]);
  });

  it("offLoopback on a fixture of the measured 4.54 table returns only TCP 9000", () => {
    // Measured against the pinned chrislusf/seaweedfs:4.54 image behind the forwarder: every store
    // listener on loopback, the forwarder's S3 port the only one off it, plus Docker's embedded DNS.
    const tcpText = `${HEADER_TCP}
${tcpLine("0B00007F:A5DB", "0A")}
${tcpLine("0100007F:2465", "0A")}
${tcpLine("0100007F:208D", "0A")}
${tcpLine("0100007F:2385", "0A")}
${tcpLine("0100007F:22B9", "0A")}
${tcpLine("0100007F:4BB9", "0A")}
${tcpLine("0100007F:47B1", "0A")}
${tcpLine("0100007F:1F90", "0A")}
${tcpLine("0100007F:46A0", "0A")}
${tcpLine("0100007F:1FA0", "0A")}
${tcpLine("00000000:2328", "0A")}
`;
    const udpText = `${HEADER_UDP}\n${udpLine("0B00007F:8C37", "07")}\n`;
    const listeners = parseProcNetListeners({ tcp: tcpText, tcp6: `${HEADER_TCP}\n`, udp: udpText, udp6: `${HEADER_UDP}\n` });
    expect(offLoopback(listeners)).toEqual([{ proto: "tcp", address: "0.0.0.0", port: 9000 }]);
  });
});
