# Migration To Java

Phase1 intentionally does not call the Java application.

Later gate requirements:

- add an authenticated adapter endpoint with explicit token handling
- define exact field semantics for amount, volume, and money flow fields
- keep model input free of user identity, cost basis, and private account data
- fail closed when source data envelope reports an error
- keep DeepSeek and email disabled until separate gates are passed
