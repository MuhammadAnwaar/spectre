package dev.solarion.anticheat.check;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.protocol.MovementStates;

import java.util.logging.Level;

public class MovementCheck {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final double MAX_STEP_HEIGHT = 1.1;
    public static final double MAX_FALLING_SPEED = 60.0;
    public static final double SPEED_BUFFER_MULTIPLIER = 1.5;
    public static final double VERTICAL_BUFFER_ADDITIVE = 2.0;
    public static final double LATERAL_BUFFER_ADDITIVE = 3.0;
    
    // Extra speed buffer for in-air combat (weapon attacks give momentum boosts)
    // This is a temporary fix until we can look into the physics engine
    public static final double COMBAT_MOMENTUM_MULTIPLIER = 4.0;

    // TODO: Return more specific info like "SPEED_1" instead of just true/false
    public static boolean checkInvalidAbsoluteMovementPacket(double x, double y, double z, Vector3d previousPosition, MovementStates movementStates, MovementManager movementManager, float deltaTime) {

        // Allow step-up (client teleports up when stepping onto blocks)
        if (movementStates.onGround && !movementStates.mantling) {
            double deltaY = y - previousPosition.y;
            if (deltaY > 0 && deltaY <= MAX_STEP_HEIGHT) {
                return false;
            }
        }

        // Block mantle-into-ceiling glitch
        if (movementStates.onGround && movementStates.mantling) {
            LOGGER.at(Level.WARNING).log("[MovementCheck] FAILED: Mantle-into-ceiling glitch");
            return true;
        }

        // Allow mantling
        if (movementStates.mantling) {
            return false;
        }

        var newPosition = new Vector3d(x, y, z);
        var delta = newPosition.clone().subtract(previousPosition);

        // Check horizontal speed
        var lateralDelta = new Vector3d(delta.x, 0, delta.z);
        var lateralSpeed = lateralDelta.length() / deltaTime;
        double lateralLimit = getLateralLimit(movementStates, movementManager);

        if (lateralSpeed > lateralLimit) {
            LOGGER.at(Level.WARNING).log("[MovementCheck] FAILED: Lateral speed %.2f > %.2f", lateralSpeed, lateralLimit);
            return true;
        }

        // Check vertical speed (only fail if not falling)
        var verticalSpeed = Math.abs(delta.y) / deltaTime;
        double verticalLimit = getVerticalLimit(movementStates, movementManager, delta.y);

        if (verticalSpeed > verticalLimit && !movementStates.falling) {
            LOGGER.at(Level.WARNING).log("[MovementCheck] FAILED: Vertical speed %.2f > %.2f", verticalSpeed, verticalLimit);
            return true;
        }

        return false;
    }

    private static double getVerticalLimit(MovementStates movementStates, MovementManager movementManager, double deltaY) {
        var settings = movementManager.getSettings();
        double maxVerticalSpeed;
        
        if (movementStates.flying) {
            maxVerticalSpeed = settings.verticalFlySpeed;
        } else if (deltaY > 0) {
            // Jumping - cap at jump force
            maxVerticalSpeed = settings.jumpForce;
        } else {
            // Falling - loose cap
            maxVerticalSpeed = MAX_FALLING_SPEED;
        }

        return (maxVerticalSpeed * SPEED_BUFFER_MULTIPLIER) + VERTICAL_BUFFER_ADDITIVE;
    }

    private static double getLateralLimit(MovementStates movementStates, MovementManager movementManager) {
        var settings = movementManager.getSettings();
        double maxLateralSpeed;
        
        if (movementStates.flying) {
            maxLateralSpeed = settings.horizontalFlySpeed;
        } else {
            // Diagonal sprint speed = hypot(forward, strafe)
            double forwardComponent = settings.baseSpeed * settings.forwardSprintSpeedMultiplier;
            double strafeComponent = settings.baseSpeed * settings.strafeRunSpeedMultiplier;
            maxLateralSpeed = Math.hypot(forwardComponent, strafeComponent);
            
            // In air: apply air multipliers + combat momentum for weapon attacks
            if (!movementStates.onGround) {
                maxLateralSpeed *= settings.airSpeedMultiplier;
                maxLateralSpeed *= settings.comboAirSpeedMultiplier;
                // TODO: Look at movesettings or something deeper within Hytales 'physics engine' instead of setting our own multiplier
                maxLateralSpeed *= COMBAT_MOMENTUM_MULTIPLIER;
            }
        }

        return (maxLateralSpeed * SPEED_BUFFER_MULTIPLIER) + LATERAL_BUFFER_ADDITIVE;
    }
}
