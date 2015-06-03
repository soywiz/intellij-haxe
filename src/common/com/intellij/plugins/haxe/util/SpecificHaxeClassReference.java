/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.util;

import com.intellij.plugins.haxe.lang.psi.HaxeClass;

public class SpecificHaxeClassReference {
  static public SpecificHaxeClassReference[] EMPTY = new SpecificHaxeClassReference[0];
  public HaxeClassReference clazz;
  public SpecificHaxeClassReference[] specifics;

  public SpecificHaxeClassReference(HaxeClassReference clazz, SpecificHaxeClassReference[] specifics) {
    this.clazz = clazz;
    this.specifics = specifics;
  }

  static public SpecificHaxeClassReference ensure(SpecificHaxeClassReference clazz) {
    return (clazz != null) ? clazz : new SpecificHaxeClassReference(null, EMPTY);
  }

  static public SpecificHaxeClassReference withoutGenerics(HaxeClassReference clazz) {
    return new SpecificHaxeClassReference(clazz, EMPTY);
  }

  static public SpecificHaxeClassReference withGenerics(HaxeClassReference clazz, SpecificHaxeClassReference[] specifics) {
    return new SpecificHaxeClassReference(clazz, specifics);
  }

  @Override
  public String toString() {
    if (this.clazz == null) return "Unknown";
    String out = this.clazz.getName();
    if (specifics.length > 0) {
      out += "<";
      for (int n = 0; n < specifics.length; n++) {
        if (n > 0) out += ", ";
        out += specifics[n].toString();
      }
      out += ">";
    }
    return out;
  }
}
