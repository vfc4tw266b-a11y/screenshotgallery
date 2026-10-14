package com.example.screenshotgallery;

import net.minecraft.resources.Identifier;

/** Eine geladene Textur (Kennung + Groesse in Pixeln). */
record Tex(Identifier id, int w, int h) {}
