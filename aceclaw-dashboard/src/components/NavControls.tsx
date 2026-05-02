/**
 * NavControls — bottom-right floating control overlay for the
 * ExecutionTree. Four buttons + a zoom-percentage readout.
 *
 *   −   zoom out      (Cmd/Ctrl + -)
 *   +   zoom in       (Cmd/Ctrl + +)
 *   ⤢   fit to window (F   or Cmd/Ctrl + 0)
 *   ⊙   jump to active node (A)
 *
 * The component is purely presentational — no viewport state of its
 * own. ExecutionTree owns the math (via ./viewport helpers) and just
 * calls the corresponding handler when a button or shortcut fires.
 */

interface NavControlsProps {
  /** Current zoom scale, used for the percentage readout. */
  scale: number;
  /** Disables the Active button when there's no active node to jump to. */
  canJumpToActive: boolean;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFit: () => void;
  onJumpToActive: () => void;
}

export function NavControls({
  scale,
  canJumpToActive,
  onZoomIn,
  onZoomOut,
  onFit,
  onJumpToActive,
}: NavControlsProps) {
  return (
    <div
      // pointer-events:auto re-enables clicks (the parent overlay
      // turned them off so the SVG below can still receive wheel/drag).
      // stopPropagation on the pointer events is critical: ExecutionTree's
      // root onPointerDown calls setPointerCapture for canvas-drag, which
      // would otherwise steal the subsequent pointerup from the button
      // and prevent the synthesized click from ever firing. Mirrors what
      // PermissionPanel does for the same reason.
      onPointerDown={(e) => e.stopPropagation()}
      onPointerMove={(e) => e.stopPropagation()}
      onPointerUp={(e) => e.stopPropagation()}
      onClick={(e) => e.stopPropagation()}
      onWheel={(e) => e.stopPropagation()}
      className="pointer-events-auto flex flex-col items-end gap-1 rounded-lg border border-zinc-800 bg-zinc-900/90 p-1 shadow-lg backdrop-blur-sm"
      role="toolbar"
      aria-label="Tree navigation controls"
    >
      <div className="flex items-center gap-1">
        <NavButton
          label="Zoom out"
          shortcut="Cmd/Ctrl + −"
          onClick={onZoomOut}
        >
          −
        </NavButton>
        <NavButton
          label="Zoom in"
          shortcut="Cmd/Ctrl + +"
          onClick={onZoomIn}
        >
          +
        </NavButton>
        <NavButton
          label="Fit to window"
          shortcut="F"
          onClick={onFit}
        >
          {/* ⤢ — diagonal arrows out, signals "expand to fill". */}
          ⤢
        </NavButton>
        <NavButton
          label="Jump to active node"
          shortcut="A"
          onClick={onJumpToActive}
          disabled={!canJumpToActive}
        >
          {/* ⊙ — target / focus glyph, signals "centre on current". */}
          ⊙
        </NavButton>
      </div>
      <div
        className="px-1 font-mono text-[10px] text-zinc-500"
        aria-live="polite"
      >
        {Math.round(scale * 100)}%
      </div>
    </div>
  );
}

interface NavButtonProps {
  label: string;
  shortcut: string;
  onClick: () => void;
  disabled?: boolean;
  children: React.ReactNode;
}

function NavButton({ label, shortcut, onClick, disabled, children }: NavButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={`${label} (${shortcut})`}
      aria-label={label}
      className="flex h-7 w-7 items-center justify-center rounded text-sm text-zinc-300 transition-colors hover:bg-zinc-800 hover:text-zinc-100 disabled:cursor-not-allowed disabled:text-zinc-600 disabled:hover:bg-transparent"
    >
      {children}
    </button>
  );
}
