// The page polls the v2 version stamp and fetches the full view only when it changed (or on first load / on demand).
export function shouldLoadFull(lastVersion, version, force) {
  return Boolean(force) || !lastVersion || !version || version !== lastVersion
}
