# NOTE: GitHub will not get releases published anymore, for the latest release, check [Modrinth](https://modrinth.com/mod/seymouranalyzer)

# Seymour Analyzer - Fabric Mod

A Fabric 26.1.2 client-side mod for Hypixel Skyblock that analyzes and tracks dyed leather armor colors in Hypixel Skyblock.

**License:** GPL-3.0-or-later

## Features

✅ **Color Analysis**
- Analyzes leather armor hex colors using CIEDE76 color difference (Delta E)
- Matches against 1000+ target colors from the game
- Supports fade dyes, custom colors, and normal dyes
- Tier system (T1, T2, T2+) based on color accuracy

✅ **Collection Management**
- Scans chests and tracks all dyed armor pieces
- Persistent storage of collection data
- UUID tracking to avoid duplicates
- Export functionality

✅ **Pattern & Word Detection**
- Detects special hex patterns (paired, repeating, palindrome, AxBxCx)
- Custom word matching with hex patterns
- Pattern highlighting in GUI

✅ **GUI Features**
- Database GUI showing all collected pieces
- Info box overlay when hovering over armor
- Sort and filter capabilities
- Search by hex, name, or color match

✅ ** Important Commands**
- `/seymour` - Show help menu -  Get all available commands there
- `/seymour config` - Open config GUI
- `/seymour scan start/stop` - Start/stop chest scanning
- `/seymour db` - Open database GUI
- `/seymour weight` - Calculate workbook-derived collection weight by profile
- `/seymour toggle <option>` - Toggle features
- `/seymour add <name> <hex>` - Add custom color
- `/seymour word add <word> <pattern>` - Add word pattern
- `/seymour list` - List custom colors
- `/seymour clear` - Clear collection
- `/seymour stats` - Show statistics

## Installation

1. Make sure you use [Fabric Loader 18.4+](https://fabricmc.net/use/) for Minecraft 1.21.11
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download the latest SeymourAnalyzer jar from releases
4. Place the jar in your `.minecraft/mods` folder
5. Launch Minecraft with the Fabric profile

## Usage

### Scanning Armor

1. Run `/seymour scan start` to enable scanning
2. Open any chest containing dyed leather armor
3. The mod will automatically analyze and save pieces
4. Run `/seymour scan stop` to disable scanning

### Viewing Collection

- Use `/seymour db` to open the database GUI
- Scroll through your collection
- View color matches and Delta E values
- Sort by various criteria

### Collection Statistics

View detailed statistics about your collection:
```
/seymour stats
```

Shows:
- **Total pieces** in your collection
- **By Tier**: T1< (ΔE < 1.0), T1 (1.0-2.0), T2 (2.0-3.0), T3+ (3.0+)
- **By Type**: Normal colors, fade dyes, custom colors
- **Special Features**: Pieces with patterns and word matches
- **Duplicates**: Unique hex codes with multiple pieces

### Custom Colors

Add your own target colors:
```
/seymour add "My Color Name" FF00AA
```

### Word Patterns

Add hex patterns that spell words (supports wildcards):
```
/seymour word add DEAD DEAD00
/seymour word add BEEF 00BEEF
/seymour word add ILikeWildcards 1x3x5x
```

### Toggles

Available toggle options:
- `infobox` - Info box overlay
- `highlights` - Item highlights in chests
- `fade` - Include fade dyes in matching
- `3p` - Filter 3-piece sets
- `sets` - Piece-specific matching
- `words` - Word pattern detection
- `pattern` - Special pattern detection
- `custom` - Custom color matching
- `dupes` - Duplicate highlighting
- `highfades` - Show fade dye matches with ΔE > 2.00 (T3+)
- `itemframes` - Scan armor in item frames

## Configuration

Config files are stored in `.minecraft/config/seymouranalyzer/`:

- `armorChecklistCache.json` - Cached armor checklist entries
- `config.json` - Mod settings
- `collection.json` - Your scanned armor pieces
- `data.json` - Custom colors and word patterns

## Development

### Building from Source

```bash
git clone https://github.com/Schnerry/SeymourAnalyzer
cd SeymourAnalyzer
./gradlew build
```

The built jar will be in `build/libs/`

### Running in Development

```bash
./gradlew runClient
```

## Ported from ChatTriggers

This mod is a complete Java rewrite of the original ChatTriggers JavaScript module for Minecraft 1.8.9. All features have been ported to work natively with Fabric on 1.21.8 without requiring ChatTriggers.

## License

This project is licensed under the GNU General Public License v3.0 or later - see the [LICENSE](LICENSE) file for details.

**Dependencies:**
- [Fabric API](https://modrinth.com/mod/fabric-api) - Apache License 2.0 (compatible)
- [Cloth Config](https://modrinth.com/mod/cloth-config) - LGPL-3.0 (compatible)

## Credits

- Original ChatTriggers module by AllyMe
- Fabric API and Fabric Loader teams
- Cloth Config API by shedaniel
- Color database compiled from Hypixel Skyblock

## Support

For bugs, feature requests, or questions:
- DM me on Discord: Schnerry

---

**Note:** This is a client-side mod for Hypixel Skyblock. It does not modify any server-side behavior and complies with Hypixel's mod rules.
