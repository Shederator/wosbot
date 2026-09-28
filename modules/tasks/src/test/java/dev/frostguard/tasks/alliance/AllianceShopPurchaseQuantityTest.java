package dev.frostguard.tasks.alliance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AllianceShopPurchaseQuantityTest {

    @Test
    void quantityOneStaysOneWhenStockIsLarger() {
        assertEquals(1, AllianceShopRoutine.computeBuyQtyFlow(100, 90, 10, 8));
    }

    @Test
    void quantityIsBoundedByValidatedStockAndReserve() {
        assertEquals(3, AllianceShopRoutine.computeBuyQtyFlow(150, 100, 10, 3));
        assertEquals(0, AllianceShopRoutine.computeBuyQtyFlow(100, 100, 10, 8));
    }
}
