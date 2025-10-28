## Supported call sites
- `Any\Ns\Funcs::route('Apis', 'route.name')`
- `anyprefix_route('Apis', 'route.name')`  (function name ends with `_route`)
- `$this->route('Apis', 'route.name')` or `$obj->route(...)`

## Mapping file(s)
Place `.wpsp-routes.json` into each plugin directory:
- `wp-content/plugins/<plugin>/.wpsp-routes.json`

You can also add a root-level `.wpsp-routes.json` if you prefer. All found files are merged.
Lines are **1-based** in your JSON.

**Format example**
```json
{
    "scope": "wpsp",
    "Apis": {
        "wpsp.api-token.get":  { "file": "wp-content/plugins/wpsp/Apis.php",  "line": 27 },
        "wpsp.api-token.test": { "file": "wp-content/plugins/wpsp/Apis.php",  "line": 28 }
    },
    "Ajaxs": {
        "wpsp.handle_database": { "file": "wp-content/plugins/wpsp/Ajaxs.php", "line": 22 }
    }
}
```

## Build (locally)
- Install **JDK 17**
- Open the project in PhpStorm → Gradle tool window → run task `intellij > buildPlugin`
  or run on CLI: `./gradlew build`
- Your plugin zip will be at: `build/distributions/wpsp-helper-{version}.zip`
- Install zip in PhpStorm: Settings → Plugins → ⚙ → Install from Disk.

## Notes
- Auto-reloads mapping when any `.wpsp-routes.json` changes.
- If no mapping file is present, the plugin is effectively dormant (no references added).
