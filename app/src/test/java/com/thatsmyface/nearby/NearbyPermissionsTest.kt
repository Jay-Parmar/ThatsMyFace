package com.thatsmyface.nearby

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPermissionsTest {
    @Test fun android10And11UseDiscoveryLocationWithoutNewBluetoothPermissions() {
        val location = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(location, NearbyPermissions.required(29).toSet())
        assertEquals(location, NearbyPermissions.required(30).toSet())
    }

    @Test fun android12RequestsBluetoothAndBothLocationPermissionsTogether() {
        listOf(31, 32).forEach { sdk ->
            val permissions = NearbyPermissions.required(sdk)
            assertTrue(permissions.containsAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)))
            assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        }
    }

    @Test fun android13AndLaterRequestNearbyDevicesWithoutLocation() {
        listOf(33, 34, 35, 36).forEach { sdk ->
            assertEquals(setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.NEARBY_WIFI_DEVICES),
                NearbyPermissions.required(sdk).toSet())
        }
    }

    @Test fun nearbyNeverRequestsGalleryOrBackgroundLocation() {
        (29..36).forEach { sdk ->
            val permissions = NearbyPermissions.required(sdk)
            assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
            assertFalse(Manifest.permission.READ_MEDIA_IMAGES in permissions)
            assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in permissions)
        }
    }
}
