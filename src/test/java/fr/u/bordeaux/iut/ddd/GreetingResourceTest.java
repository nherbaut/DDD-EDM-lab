package fr.u.bordeaux.iut.ddd;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class GreetingResourceTest {
    @Test
    void testHelloEndpoint() {
        given()
          .when().get("/hello")
          .then()
             .statusCode(200)
             .body(is("Hello from Quarkus REST"));
    }

    @Test
    void testHomePageEndpoint() {
        given()
          .when().get("/")
          .then()
             .statusCode(200)
             .body(containsString("CloudCatcher"))
             .body(containsString("Log in"));
    }

}
