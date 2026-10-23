#!/system/bin/sh
# patch-shaders.sh GAME_DIR: makes the game's GLSL 1.20 shaders valid GLSL ES 1.00, which is what gl4es
# turns them into. The app runs it after importing the game (DDActivity.importGame); by hand, run it on
# the phone (as root or as the app) against the game copy, never the PC install. Originals are kept as
# *.glsl.orig and every run starts again from them.
# - blur_*: no initialised uniform arrays / array constructors in ES: plain globals set in main().
# - character*: no implicit int -> float conversion in ES.
# Their colour lookup cubes (texture3D) need no change: gl4es makes them GLES 3D textures through
# GL_OES_texture_3D (patches/gl4es-3d-textures.patch).
# Write through the FUSE path (/storage/emulated/0/...), not /data/media: changes made underneath
# FUSE leave the app seeing the old file sizes (the game then reads truncated shaders).
cd "$1/shaders" || exit 1

# keep the originals first: in "orig f | ... > f" the shell empties f before orig can copy it
for f in blur_horiz.glsl blur_vert.glsl character.glsl character_stealth.glsl film.glsl town_post_effects.glsl; do
  [ -s $f.orig ] || cp -p $f $f.orig || exit 1
done
orig() {  # orig FILE: print the original text of FILE
  cat $1.orig
}

for f in blur_horiz.glsl blur_vert.glsl; do
  orig $f | sed \
      -e 's/^uniform float offset\[3\] = float\[\]( 0.0, 1.3846153846, 3.2307692308 );/float offset[3];/' \
      -e 's/^uniform float weight\[3\] = float\[\]( 0.2270270270, 0.3162162162, 0.0702702703 );/float weight[3];/' |
    awk '{ print }
         fragment && /^void main\(void\)$/ { setup = 1 }
         setup && /^\{$/ { print "\toffset[0] = 0.0; offset[1] = 1.3846153846; offset[2] = 3.2307692308;"
                           print "\tweight[0] = 0.2270270270; weight[1] = 0.3162162162; weight[2] = 0.0702702703;"
                           setup = 0 }
         /##Fragment/ { fragment = 1 }' > $f
done

for f in character.glsl character_stealth.glsl film.glsl town_post_effects.glsl; do
  orig $f | sed \
      -e 's/(YGradientStartLine - 30)/(YGradientStartLine - 30.0)/' \
      -e 's/3\.141569 \* 2 )/3.141569 * 2.0 )/g' \
      -e 's/ ) + 1 ) \/ 2\.0;/ ) + 1.0 ) \/ 2.0;/g' > $f
done

for f in *.glsl; do
  [ -s $f ] || { echo "$f: empty"; exit 1; }
  grep -q 'float\[\]' $f && { echo "$f: still has GLSL 1.20-only code"; exit 1; }
done
echo "patched shaders in $1/shaders"
