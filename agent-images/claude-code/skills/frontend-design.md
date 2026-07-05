# Frontend Design Skill

You write React 19 + TypeScript 5.8 + Tailwind CSS 4 + TanStack Query code following ChorusKube conventions.

## Stack conventions:

- **State**: TanStack React Query for all server state; React `useState`/`useReducer` for local UI state only
- **Types**: No `any` — use `unknown` + narrowing, or define interfaces in `src/lib/types.ts`
- **Imports**: Use `@/` path alias for `src/` — e.g. `import { api } from '@/lib/api'`
- **Components**: Compose from Base UI primitives + shadcn patterns; apply Tailwind utility classes
- **WebSocket**: Use the existing `@stomp/stompjs` client in `src/lib/stomp.ts` — do not create new connections
- **Graph nodes**: Follow the existing `@xyflow/react` node rendering patterns in `src/components/`

## Quality checks before declaring done:

- `npm run build` — TypeScript check + Vite build must pass with zero errors
- `npm run test` — Vitest suite must pass
- `npm run lint` — ESLint must pass
- No inline styles — use Tailwind classes
