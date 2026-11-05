import { closeSync, fstatSync, openSync, readSync } from 'node:fs'
import { inflateRawSync } from 'node:zlib'

/** The dev server's zip reading: the central directory, then one entry at a time, like core/epub's ZipReader. No Zip64. */
export type ZipEntry = { name: string; method: number; compressedSize: number; size: number; offset: number; encrypted: boolean }
export type Zip = { entries: ZipEntry[]; entry(path: string): ZipEntry | null; read(e: ZipEntry): Buffer; close(): void }

const END_SIG = 0x06054b50
const CENTRAL_SIG = 0x02014b50
const LOCAL_SIG = 0x04034b50
const FULL32 = 0xffffffff

export function openZip(path: string): Zip {
  const fd = openSync(path, 'r')
  const at = (position: number, length: number) => {
    const b = Buffer.alloc(length)
    const n = readSync(fd, b, 0, length, position)
    return n === length ? b : b.subarray(0, n)
  }
  try {
    const size = fstatSync(fd).size
    const tailLength = Math.min(size, 22 + 0xffff)
    const tail = at(size - tailLength, tailLength)
    let end = -1
    for (let i = tail.length - 22; i >= 0 && end < 0; i--) if (tail.readUInt32LE(i) === END_SIG) end = i
    if (end < 0) throw new Error(`${path}: not a zip`)
    const count = tail.readUInt16LE(end + 10)
    const cdSize = tail.readUInt32LE(end + 12)
    const cdOffset = tail.readUInt32LE(end + 16)
    if (count === 0xffff || cdSize === FULL32 || cdOffset === FULL32) throw new Error(`${path}: Zip64 is not supported here`)
    const cd = at(cdOffset, cdSize)
    const entries: ZipEntry[] = []
    for (let p = 0, i = 0; i < count; i++) {
      if (p + 46 > cd.length || cd.readUInt32LE(p) !== CENTRAL_SIG) throw new Error(`${path}: damaged central directory`)
      const nameLength = cd.readUInt16LE(p + 28)
      entries.push({
        name: cd.toString('utf8', p + 46, p + 46 + nameLength),
        method: cd.readUInt16LE(p + 10),
        compressedSize: cd.readUInt32LE(p + 20),
        size: cd.readUInt32LE(p + 24),
        offset: cd.readUInt32LE(p + 42),
        encrypted: (cd.readUInt16LE(p + 8) & 1) !== 0,
      })
      p += 46 + nameLength + cd.readUInt16LE(p + 30) + cd.readUInt16LE(p + 32)
    }
    const byName = new Map<string, ZipEntry>()
    const byLower = new Map<string, ZipEntry>()
    for (const e of entries) {
      if (!byName.has(e.name)) byName.set(e.name, e)
      if (!byLower.has(e.name.toLowerCase())) byLower.set(e.name.toLowerCase(), e)
    }
    return {
      entries,
      entry: name => {
        const p = name.replace(/\\/g, '/').replace(/^\//, '')
        return byName.get(p) ?? byLower.get(p.toLowerCase()) ?? null
      },
      read: e => {
        if (e.encrypted) throw new Error(`encrypted entry ${e.name}`)
        const local = at(e.offset, 30)
        if (local.length < 30 || local.readUInt32LE(0) !== LOCAL_SIG) throw new Error(`bad local header for ${e.name}`)
        const data = at(e.offset + 30 + local.readUInt16LE(26) + local.readUInt16LE(28), e.compressedSize)
        if (data.length < e.compressedSize) throw new Error(`entry ${e.name} beyond end of file`)
        if (e.method === 0) return data
        if (e.method === 8) return inflateRawSync(data)
        throw new Error(`entry ${e.name}: unknown method ${e.method}`)
      },
      close: () => closeSync(fd),
    }
  } catch (e) {
    closeSync(fd)
    throw e
  }
}
