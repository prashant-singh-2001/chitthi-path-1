# Chitthi frontend

A minimal React + TypeScript page: upload a document, watch its pages move
through the pipeline live over Server-Sent Events, then play the finished
audio. Deliberately thin - the backend pipeline is the showcase.

## Development

```
npm install
npm run dev
```

The dev server proxies `/api` to `http://localhost:8080` (see
`vite.config.ts`), so run the Spring Boot app alongside it.

## Testing and build

```
npm test     # vitest, currently the pure helpers in src/api/progress.ts
npm run build  # tsc -b && vite build
npm run lint   # oxlint
```
