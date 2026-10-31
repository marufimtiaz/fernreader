import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { Bridge } from '../src/bridge/bridge'

const fixture = (n: string) => readFileSync(resolve(__dirname, `../../bridge/fixtures/${n}.json`), 'utf8').trim()

describe('Bridge', () => {
  it('answers ping with pong of the same id', () => {
    const post = vi.fn()
    const b = new Bridge(post)
    b.receive(fixture('ping'))
    expect(JSON.parse(post.mock.calls[0][0])).toEqual(JSON.parse(fixture('pong')))
  })
  it('serializes ready exactly like the fixture', () => {
    const post = vi.fn()
    new Bridge(post).send({ v: 1, type: 'ready', engine: 'foliate-js@78914ae' })
    expect(JSON.parse(post.mock.calls[0][0])).toEqual(JSON.parse(fixture('ready')))
  })
  it('ignores unknown message types without throwing', () => {
    const post = vi.fn()
    expect(() => new Bridge(post).receive(fixture('unknown'))).not.toThrow()
    expect(post).not.toHaveBeenCalled()
  })
})
