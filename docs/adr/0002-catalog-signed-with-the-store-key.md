# The Catalog is signed with the Store's own key

A silent installer is the most powerful app on the TV, so the Catalog must not be something anyone who can write to the GitHub release can change. The Resolver signs `catalog.json` with the Store's release key, and the Store accepts a Catalog only if the signature matches the key the Store itself is signed with. Each APK must then match the SHA-256 in the Catalog, and Android refuses an update signed with a different key than the installed App.

A separate Catalog key would keep the two jobs apart, but it would mean one more secret and a public key to bake into the app. Using one key means nothing new to set up, and that key already has to stay private. If the key ever leaks, both the Store and the Catalog get a new key with the next system update.
