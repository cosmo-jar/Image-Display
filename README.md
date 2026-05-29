<img width="2172" height="724" alt="logoooooo" src="https://github.com/user-attachments/assets/4807e40f-a056-4f3b-aafe-d5d9a86a6deb" />

# Where can it be used?
Turn any image into a grid of MineSkin-powered head textures, then use it in chat, TAB header/footer, prefixes/suffixes, nametags, holograms, titles, scoreboard and other.
Image Display is perfect for server logos, icons, decorations, rank visuals, hologram art, lobby branding, and other visual UI elements without forcing a resource pack.

## Features
* Display images without a resource pack
* Auto convert PNG/JPG/JPEG/WEBP images into player-head components. You don't need to manually crop images - the plugin will do it for you!
* You don't need to mess with resource bundles or search for glyphs, just upload your images and use a single placeholder!
* Use images with simple placeholders like: 
```
%imagedisplay_<image_name>%
```
* Works in any chat through ProtocolLib component replacement
* Uses local JSON image cache after generation
* Permission control for usage
* Fallback text for unsupported clients 
<details>
<summary>Spoiler</summary>

Clients before version 1.21.9 cannot see such images. For them, you can specify a fallback text in the config

</details>

<img width="1672" height="941" alt="TAB (1)" src="https://github.com/user-attachments/assets/e761d7ce-2bbd-40a3-9bb4-2eaa217008e1" />


## How to add and use any image?


**1. Upload your image**

Put your image into:

```text
plugins/ImageDisplay/pictures/
```

⚠️ Make sure the image size is a multiple of 8 pixels.

Examples:

```text
16x16
32x32
64x8
64x64
80x8
96x8
128x64
128x128
256x128
```

**2. Specify your MineSkin API key in the plugin configuration**
It's free: [You can get it here](https://mineskin.org/apikey)

Paste API key to:

```
mineskin-api-key: "here"
```


**3. Generate the image data**

Run the command:

```text
/imagedisplay generate your_image_name.png
```

**4. Wait generation**

Wait for a chat message about successful generation, or check the console.

**5. Use the placeholder**

Use the generated placeholder anywhere supported:

```text
%imagedisplay_your_image_name%
```

## Permissions

Every generated image gets its own permission node:

```text
imagedisplay_<id>
```

Example:

```text
%imagedisplay_logo%
```

requires:

```text
imagedisplay_logo
```

This permission check is applied only when players try to use Image Display placeholders in chat. I plan to expand the permissions in the future.

### Required plugins

Image Display requires:

* **PlaceholderAPI** — for placeholders
* **ProtocolLib** — for image display and detection client version

Optional:

* **ViaVersion** — useful for for detection client version

### How it works?

Image Display takes an image, splits it into 8x8 pixel tiles, sends those tiles to MineSkin, and receives signed player head textures.

The plugin then stores the result in local JSON cache files. After generation, the server does not need to process the original image again. The placeholder simply renders the cached head components. Image Display is designed to be lightweight during normal server usage.

### Tested and works with plugins:
* TAB
* Chatty
* ChatEx
* Citizen
* FancyNPC
* DeluxeHub
* LuckPerms
* EssentialsX 
* EssentialsX Chat
* FancyHolograms
* LPC-Minimessage
* DecentHolograms

and more....

<img width="1334" height="1179" alt="Scoreboard" src="https://github.com/user-attachments/assets/20ee38c7-bbb1-4e95-a86a-bc4f71900c6d" />

### Notes

* If you use plugin TAB then has  default MiniMessage support enabled for components — image rendering not work in prefixes/suffixes. Disable it in TAB config:

```yaml
components:
  minimessage-support: false
```

Then run the command

```text
/tab reload
```
* Support for multiple localizations
* I recommend fonts: Minecraftia, Press Start 2P or any bitmap-style.
