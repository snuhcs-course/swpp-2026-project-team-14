package com.swpp.stylemate.data

import androidx.core.content.FileProvider

/**
 * Shares the temporary camera files of body and worn photos (`res/xml/photo_paths.xml`).
 *
 * It needs its own class: Android identifies a provider by its class name, so a second
 * `androidx.core.content.FileProvider` entry next to the wardrobe-capture provider is dropped,
 * and camera apps then fail with "authority ... does not match".
 */
class PhotoFileProvider : FileProvider()
