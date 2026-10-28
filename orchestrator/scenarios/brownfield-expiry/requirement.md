# Optional link expiry

Marketing creates short links for time-limited campaigns and wants them to stop working when the campaign ends.

## Acceptance criteria

- When creating a short URL, the form offers an optional expiry, stored as `expiresInDays` between 1 and 365.
- An expired short link responds with HTTP 410 Gone instead of redirecting.
- Links created without `expiresInDays` never expire, and existing links keep working.
- Stats for a link include its `expiresAt` date.
- The README documents the new field and the 410 response.
