import { cx } from "../util";

/** The offline Nanodot application mark from public/icon.svg. */
export function BrandMark({ size = 72, className }: { size?: number; className?: string }) {
  return <img src="/icon.svg" alt="Nanodot" width={size} height={size} draggable={false} className={cx("block shrink-0 select-none", className)} style={{ width: size, height: size }} />;
}
