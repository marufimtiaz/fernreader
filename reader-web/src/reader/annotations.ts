/**
 * F-060/F-061 user annotations as drawn on the page. The app owns them (database, original CFIs) and sends each one
 * with `addAnnotation` (virtual CFI); this side only draws them with foliate's overlayer.
 */

/**
 * The six highlight colours (ids as in core/data HighlightColors; the same values as the app's swatches in
 * ui/.../reader/selection/HighlightPalette.kt). Overlayer.highlight draws them at 0.3 opacity.
 */
export const HIGHLIGHT_COLORS: Readonly<Record<string, string>> = {
  yellow: '#fdd835',
  green: '#66bb6a',
  blue: '#42a5f5',
  pink: '#f06292',
  orange: '#ffa726',
  purple: '#ab47bc',
}

export type AnnotationStyle = 'highlight' | 'underline'

export type DrawnAnnotation = { id: string; cfi: string; color: string; style: AnnotationStyle; index: number }

/** Which overlayer function draws an annotation and in which CSS colour (unknown colour ids draw yellow). */
export function annotationDraw(style: AnnotationStyle, color: string): { kind: AnnotationStyle; color: string } {
  return { kind: style, color: HIGHLIGHT_COLORS[color] ?? HIGHLIGHT_COLORS.yellow }
}
