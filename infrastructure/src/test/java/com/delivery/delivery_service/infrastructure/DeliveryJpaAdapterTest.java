package com.delivery.delivery_service.infrastructure;

import com.delivery.delivery_service.entity.Delivery;
import com.delivery.delivery_service.entity.DeliveryStatus;
import com.delivery.delivery_service.repository.DeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:delivery-infrastructure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@EntityScan("com.delivery.delivery_service.entity")
@EnableJpaRepositories("com.delivery.delivery_service.repository")
@ContextConfiguration(classes = DeliveryJpaAdapterTest.JpaConfig.class)
class DeliveryJpaAdapterTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class JpaConfig { }

    @Autowired
    private DeliveryRepository repository;

    @Test
    void persistsDeliveryAndExecutesOrderQuery() {
        Delivery delivery = new Delivery();
        delivery.setCreateEventId(UUID.randomUUID());
        delivery.setOrderId(901L);
        delivery.setCreatorId(77L);
        delivery.setStatus(DeliveryStatus.PENDING);

        Delivery saved = repository.saveAndFlush(delivery);

        assertThat(repository.findByOrderId(901L)).get()
                .extracting(Delivery::getId, Delivery::getStatus)
                .containsExactly(saved.getId(), DeliveryStatus.PENDING);
    }
}
