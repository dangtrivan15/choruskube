// SYNC: shared brand mark. Two byte-identical copies exist; keep them in lockstep:
//   - choruskube-landing/src/components/Logo.tsx
//   - choruskube/web-ui/src/components/Logo.tsx
// Each repo's public/favicon.svg must mirror this artwork.

type Props = {
  size?: number
  className?: string
  'aria-label'?: string
}

export default function Logo({ size = 22, className, ...rest }: Props) {
  const label = rest['aria-label']
  const decorative = !label
  return (
    <svg
      data-testid="logo-mark"
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 64 64"
      width={size}
      height={size}
      className={className}
      role={decorative ? undefined : 'img'}
      aria-hidden={decorative ? true : undefined}
      aria-label={label}
    >
      <defs>
        <linearGradient id="choruskube-logo-grad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#907aa9" />
          <stop offset="1" stopColor="#56949f" />
        </linearGradient>
      </defs>
      <rect x="4" y="4" width="56" height="56" rx="14" fill="url(#choruskube-logo-grad)" />
      <g stroke="#faf4ed" strokeWidth="1.6" strokeLinecap="round" opacity="0.55">
        <line x1="22" y1="24" x2="42" y2="24" />
        <line x1="22" y1="24" x2="32" y2="42" />
        <line x1="42" y1="24" x2="32" y2="42" />
      </g>
      <g fill="#faf4ed">
        <circle cx="22" cy="24" r="5.5" />
        <circle cx="42" cy="24" r="5.5" />
        <circle cx="32" cy="42" r="5.5" />
      </g>
    </svg>
  )
}
