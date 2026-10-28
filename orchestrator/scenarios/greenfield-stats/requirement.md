# Usage statistics endpoint

Operations wants a quick view of how much the shortener is used, without querying MongoDB by hand.

## Acceptance criteria

- `GET /api/stats` returns the total number of short links and the total number of clicks as JSON.
- The statistics endpoint is read-only and needs no CSRF token.
- Statistics are aggregated in the database, not by loading every link into memory.
- The README documents the new endpoint.
