package io.openliberty.arquillian.remote.needsJunit5;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import jakarta.inject.Inject;

@ExtendWith(ArquillianExtension.class)
public class WLPJunit5TestCase {
	
   @Deployment
   public static JavaArchive createDeployment() {
       JavaArchive jar = ShrinkWrap.create(JavaArchive.class)
           .addClass(Greeter.class)
           .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
       return jar;
   }
   
   @Inject
   Greeter greeter;

   @Test
   public void should_create_greeting() {
	   Assertions.assertEquals("Hello, Earthing!", greeter.createGreeting("Earthling"));
   }
}