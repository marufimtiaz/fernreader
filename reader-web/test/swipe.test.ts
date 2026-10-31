import { describe, expect, it } from 'vitest'
import { effectiveDir, swipeToTurn } from '../src/reader/swipe'

describe('swipeToTurn (F-032)', () => {
  it('LTR: finger moving left is next, moving right is previous', () => {
    expect(swipeToTurn(-200, 10, 150, 'ltr')).toBe('next')
    expect(swipeToTurn(200, -10, 150, 'ltr')).toBe('prev')
  })
  it('RTL: mirrored, finger moving right is next', () => {
    expect(swipeToTurn(200, 0, 150, 'rtl')).toBe('next')
    expect(swipeToTurn(-200, 0, 150, 'rtl')).toBe('prev')
  })
  it('a tap or a jitter is no swipe', () => {
    expect(swipeToTurn(0, 0, 80, 'ltr')).toBeNull()
    expect(swipeToTurn(-8, 3, 60, 'ltr')).toBeNull()
  })
  it('a long press without movement is no swipe', () => {
    expect(swipeToTurn(-5, 0, 900, 'ltr')).toBeNull()
  })
  it('a slow drag released back near the start cancels', () => {
    expect(swipeToTurn(-20, 0, 1200, 'ltr')).toBeNull()
  })
  it('a short fast flick turns', () => {
    expect(swipeToTurn(-30, 2, 60, 'ltr')).toBe('next')
  })
  it('a long slow drag turns', () => {
    expect(swipeToTurn(-120, 10, 1500, 'ltr')).toBe('next')
  })
  it('a mostly vertical movement is no horizontal turn', () => {
    expect(swipeToTurn(-60, 200, 150, 'ltr')).toBeNull()
    expect(swipeToTurn(-60, 70, 150, 'ltr')).toBeNull()
  })
  it('vertical writing: an upward swipe is next, downward previous (foliate pages it top to bottom), horizontal per dir', () => {
    expect(swipeToTurn(0, -200, 150, 'rtl', true)).toBe('next')
    expect(swipeToTurn(5, 200, 150, 'rtl', true)).toBe('prev')
    expect(swipeToTurn(200, 0, 150, 'rtl', true)).toBe('next')
    expect(swipeToTurn(0, -200, 150, 'ltr', false)).toBeNull()
  })
  it('bad input is no swipe', () => {
    expect(swipeToTurn(NaN, 0, 100, 'ltr')).toBeNull()
    expect(swipeToTurn(-200, 0, 0, 'ltr')).toBe('next') // dt 0 (same timestamp): distance alone decides
  })
})

describe('effectiveDir (F-032, F-081)', () => {
  it('auto follows the book, forced values win', () => {
    expect(effectiveDir('auto', 'rtl')).toBe('rtl')
    expect(effectiveDir('auto', 'ltr')).toBe('ltr')
    expect(effectiveDir('auto', undefined)).toBe('ltr')
    expect(effectiveDir(undefined, 'rtl')).toBe('rtl')
    expect(effectiveDir('ltr', 'rtl')).toBe('ltr')
    expect(effectiveDir('rtl', 'ltr')).toBe('rtl')
  })
})
