package io.openliberty.arquillian.managed;

// Loads a class from a byte array
public class ByteClassLoader extends ClassLoader {

    public Class<?> defineClass(String name, byte[] ba) {
        return defineClass(name,ba,0,ba.length);
    }

}