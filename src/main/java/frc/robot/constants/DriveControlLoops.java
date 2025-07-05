package frc.robot.constants;

import static edu.wpi.first.units.Units.*;

import edu.wpi.first.units.measure.*;
import frc.robot.commands.drive.AutoAlignment;
import frc.robot.utils.CustomPIDs.RobotPIDController;

public class DriveControlLoops {
    public static final boolean ENABLE_SOFTWARE_CONSTRAIN = true;
    public static final AngularVelocity ANGULAR_VELOCITY_SOFT_CONSTRAIN = RotationsPerSecond.of(1.5);
    public static final AngularAcceleration ANGULAR_ACCELERATION_SOFT_CONSTRAIN = RotationsPerSecondPerSecond.of(2);
    public static final LinearVelocity MOVEMENT_VELOCITY_SOFT_CONSTRAIN = MetersPerSecond.of(4.5);
    public static final LinearVelocity AUTO_ALIGNMENT_VELOCITY_LIMIT = MetersPerSecond.of(3);
    public static final LinearAcceleration ACCELERATION_SOFT_CONSTRAIN = MetersPerSecondPerSecond.of(10);
    public static final LinearAcceleration AUTO_ALIGNMENT_ACCELERATION_LIMIT = MetersPerSecondPerSecond.of(5);
    public static final LinearVelocity MOVEMENT_VELOCITY_SOFT_CONSTRAIN_LOW = MetersPerSecond.of(2);
    public static final LinearAcceleration ACCELERATION_SOFT_CONSTRAIN_LOW = MetersPerSecondPerSecond.of(4);
    public static final AngularVelocity ANGULAR_VELOCITY_SOFT_CONSTRAIN_LOW = RotationsPerSecond.of(0.5);
    public static final AngularAcceleration ANGULAR_ACCELERATION_SOFT_CONSTRAIN_LOW = RotationsPerSecondPerSecond.of(1);

    public static final Time DISCRETIZE_TIME = Seconds.of(0.04);
    public static final LinearVelocity SWERVE_VELOCITY_DEADBAND = MetersPerSecond.of(0.03);
    public static final RobotPIDController.RobotPIDConfig CHASSIS_ROTATION_CLOSE_LOOP =
            new RobotPIDController.RobotPIDConfig(
                    Math.toRadians(300), Math.toRadians(90), 0, Math.toRadians(2), 0, true, 0);

    public static final RobotPIDController.RobotPIDConfig CHASSIS_TRANSLATION_CLOSE_LOOP =
            new RobotPIDController.RobotPIDConfig(3, 0.7, 0, 0.03, 0, false, 0);

    public static final double ROTATIONAL_LOOKAHEAD_TIME = 0.07, TRANSLATIONAL_LOOKAHEAD_TIME = 0.07;

    public static final boolean USE_TORQUE_FEEDFORWARD = false;

    public static final double AUTO_ALIGNMENT_TRANSITION_COMPENSATION_FACTOR = 0.2;
    public static final AutoAlignment.AutoAlignmentConfigurations REEF_ALIGNMENT_CONFIG_AUTONOMOUS =
            new AutoAlignment.AutoAlignmentConfigurations(
                    Meters.of(0.2),
                    MetersPerSecond.of(1.6),
                    Meters.of(0.3),
                    MetersPerSecond.of(0.4),
                    MetersPerSecondPerSecond.of(2.4));

    public static final AutoAlignment.AutoAlignmentConfigurations REEF_ALIGNMENT_CONFIG =
            new AutoAlignment.AutoAlignmentConfigurations(
                    Meters.of(0.5),
                    MetersPerSecond.of(1.6),
                    Meters.of(0.6),
                    MetersPerSecond.of(0.4),
                    MetersPerSecondPerSecond.of(2.4));

    public static final AutoAlignment.AutoAlignmentConfigurations STATION_ALIGNMENT_CONFIG =
            new AutoAlignment.AutoAlignmentConfigurations(
                    Meters.of(0.6),
                    MetersPerSecond.of(2),
                    Meters.of(0.3),
                    MetersPerSecond.of(0.6),
                    MetersPerSecondPerSecond.of(4));
}
