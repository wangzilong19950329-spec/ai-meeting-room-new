#!/usr/bin/env node

const { searchQuery } = require('./deepseek-web-client');

const query = process.argv.slice(2).join(' ').trim();

if (!query) {
  console.error('Usage: node scripts/deepseek-web-search.js "<query>"');
  process.exit(2);
}

searchQuery(query)
  .then((result) => {
    process.stdout.write(JSON.stringify(result, null, 2));
  })
  .catch((error) => {
    console.error(error.stack || error.message || String(error));
    process.exit(1);
  });
