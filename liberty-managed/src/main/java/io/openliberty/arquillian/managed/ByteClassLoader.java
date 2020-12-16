/*
 * Copyright 2020, IBM Corporation and individual contributors
 * identified by the Git commit log. 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.arquillian.managed;

// Loads a class from a byte array
public class ByteClassLoader extends ClassLoader {

    public Class<?> defineClass(String name, byte[] ba) throws IllegalAccessError {
        return defineClass(name, ba, 0, ba.length);
    }

}