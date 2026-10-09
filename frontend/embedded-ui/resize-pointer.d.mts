type ResizeOptions = {
  direction: "horizontal" | "vertical"
  edge?: "start" | "end"
  size: number
  min: number
  max: number
  collapseThreshold?: number
  onResize(size: number): void
  onCollapse?(): void
  onCollapseChange?(collapsed: boolean): void
}
export function createResizePointerHandler(local: ResizeOptions, onCleanup: (cleanup: () => void) => void):
  (event: PointerEvent & { currentTarget: HTMLElement }) => void
