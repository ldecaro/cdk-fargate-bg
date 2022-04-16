package com.example.api.runtime;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.ws.rs.core.Application;
/**
 * JAX-RS Application class for this example.
 *
 * @author Luiz Decaro
 */
public class JaxRsApplication extends Application {
    private final Set<Class<?>> classes;

    public JaxRsApplication() {
        HashSet<Class<?>> c = new HashSet<Class<?>>();
        c.add(HelloWorldResource.class);
        classes = Collections.unmodifiableSet(c);
    }

    @Override
    public Set<Class<?>> getClasses() {
        return classes;
    }
}
