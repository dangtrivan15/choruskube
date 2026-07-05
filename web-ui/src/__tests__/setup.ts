import "@testing-library/jest-dom/vitest";

// Stub setPointerCapture / releasePointerCapture — happy-dom does not implement these yet
if (!HTMLElement.prototype.setPointerCapture) {
  HTMLElement.prototype.setPointerCapture = function () {};
}
if (!HTMLElement.prototype.releasePointerCapture) {
  HTMLElement.prototype.releasePointerCapture = function () {};
}

// Stub localStorage for Node 25+ — Node 25 added a built-in localStorage global
// that shadows happy-dom's implementation, but it's non-functional (no getItem/setItem)
// unless --localstorage-file is passed. Override it with a proper in-memory Storage.
if (typeof globalThis.localStorage === "undefined" || typeof globalThis.localStorage.getItem !== "function") {
  const store = new Map<string, string>();
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    writable: true,
    value: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, String(value)),
      removeItem: (key: string) => store.delete(key),
      clear: () => store.clear(),
      get length() { return store.size; },
      key: (index: number) => [...store.keys()][index] ?? null,
    },
  });
}
