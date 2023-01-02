/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ime.zhuyin17;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Reads a phrase dictionary and provides following-word suggestions as a list
 * of characters for the given character.
 */
public class PhraseDictionary {

  private static final int APPROX_DICTIONARY_SIZE = 172032;

  private final CountDownLatch loading = new CountDownLatch(1);
  private final DictionaryLoader loader;

  public PhraseDictionary(Context context) {
    loader = new DictionaryLoader(
        context.getResources().openRawResource(R.raw.dict_phrases),
        APPROX_DICTIONARY_SIZE, loading);
    new Thread(loader).start();
  }

  /**
   * Returns a string list containing the following-word suggestions of
   * phrases for the history of committed words.
   *
   * @param history the current committed words to look for its following
   *     words of phrases.
   * @return a list of string, or an empty list if there is no following-word
   *     suggestions for that history.
   */
  public ArrayList<String> getFollowingWords(CharSequence history) {
    try {
      loading.await();
    } catch (InterruptedException e) {
      Log.e("PhraseDictionary", "Loading is interrupted: ", e);
    }

    // Phrases are stored in an array consisting of four character arrays.
    // char[0][] contains a char[] of words to look for phrases.
    // char[2][] contains a char[] of following words for char[0][].
    // char[1][] contains offsets of char[0][] words to map its following words.
    // (appended total length as sentinel)
    // char[3][] contains lengths of the following words. encoded using 2 bits.
    //
    // For example, there are 7 phrases: Aa, Ab, Accc, Bd, Bee, Bf, Cg.
    // char[0][] { A, B, C }
    // char[1][] { 0, 5, 9 }
    // char[2][] { a, b, c, c, c, d, e, e, f, g}
    // the lengths is { 1, 1, 3, _, _, 1, 2, _, 1, 1}. ("_" is unused slot)
    // the length minus one, in 2 bits:
    //   { 00, 00, 10, __, __, 00, 01, __, 00, 00}
    // the final encoding:
    // char[3][] { 0b__0100____100000, 0b00______________ }
    //         = { 0x1020, 0x0000 } ("_" filled with 0)
    // NOTE The 2 bits encoding only support length of words up to 5.
    ArrayList<String> words = new ArrayList<String>();
    char[][] dictionary = loader.result();
    if (dictionary == null || dictionary.length != 4) {
      return words;
    }

    for (int prefixLength = history.length(); prefixLength > 0; prefixLength--) {
      CharSequence prefix = history.subSequence(history.length() - prefixLength,
              history.length());
      char prefixFirstChar = prefix.charAt(0);
      CharSequence prefixRestChars = prefix.subSequence(1, prefix.length());
      int index = Arrays.binarySearch(dictionary[0], prefixFirstChar);
      if (index < 0) {
        continue;
      }
      int followingIndex = dictionary[1][index];
      int followingEndIndex = dictionary[1][index + 1];
      while (followingIndex < followingEndIndex) {
        int followingLength = decodeFollowingLength(dictionary[3],
                followingIndex);
        String following = String.valueOf(dictionary[2], followingIndex,
                followingLength);
        if (following.length() > prefixRestChars.length()) {
          if (prefixRestChars.equals(
                  following.substring(0, prefixRestChars.length()))) {
            words.add(following.substring(prefixRestChars.length()));
          }
        }
        followingIndex += followingLength;
      }
    }
    return words;
  }

  private int decodeFollowingLength(char[] encodedLength, int index) {
    // Each length is encoded in 2 bits. And 8 lengths are packed into one char.
    char packedLength = encodedLength[index / 8];
    int length = ((packedLength >> (index % 8 * 2)) & 3) + 1;
    return length;
  }
}
