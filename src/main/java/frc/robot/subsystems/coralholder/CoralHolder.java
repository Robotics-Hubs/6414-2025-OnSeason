package frc.robot.subsystems.coralholder;

import static edu.wpi.first.units.Units.*;
import static frc.robot.subsystems.coralholder.CoralHolderConstants.*;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.wpilibj.Alert;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import frc.robot.subsystems.superstructure.SuperStructureVisualizer;
import frc.robot.utils.AlertsManager;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.littletonrobotics.junction.Logger;

public class CoralHolder extends SubsystemBase {
    // Hardware interface
    private final CoralHolderIO io;
    private final CoralHolderInputsAutoLogged inputs;

    // Triggers
    public final Trigger firstSensor;
    public final Trigger secondSensor;
    /** Whether the coral is anywhere inside the intake (one of any sensor triggered). */
    public final Trigger hasCoral;
    /** Whether the coral is in place (triggering both sensors). */
    public final Trigger coralInPlace;

    // Alerts
    private final Alert motorHardwareFaultsAlert;
    private final Alert sensor1HardwareFaultsAlert;
    private final Alert sensor2HardwareFaultsAlert;

    private final Supplier<Pose2d> robotPoseSupplier;
    private final Supplier<Rotation2d> armAngleSupplier;
    private final DoubleSupplier elevatorHeightSupplier;

    public CoralHolder(
            CoralHolderIO io,
            Supplier<Pose2d> robotPoseSupplier,
            Supplier<Rotation2d> armAngleSupplier,
            DoubleSupplier elevatorHeightSupplier) {
        this.io = io;
        inputs = new CoralHolderInputsAutoLogged();

        final double FIRST_SENSOR_THRESHOLD_MM = FIRST_SENSOR_THRESHOLD.in(Millimeters);
        final double SECOND_SENSOR_THRESHOLD_MM = SECOND_SENSOR_THRESHOLD.in(Millimeters);
        this.firstSensor = new Trigger(
                () -> inputs.firstSensorReadingValid && inputs.firstSensorDistanceMM < FIRST_SENSOR_THRESHOLD_MM);
        this.secondSensor = new Trigger(
                () -> inputs.secondSensorReadingValid && inputs.secondSensorDistanceMM < SECOND_SENSOR_THRESHOLD_MM);
        this.hasCoral = firstSensor.or(secondSensor);
        this.coralInPlace = firstSensor.and(secondSensor);
        this.robotPoseSupplier = robotPoseSupplier;
        this.armAngleSupplier = armAngleSupplier;
        this.elevatorHeightSupplier = elevatorHeightSupplier;

        this.motorHardwareFaultsAlert =
                AlertsManager.create("Coral Holder roller motor hardware faults detected!", Alert.AlertType.kError);
        this.sensor1HardwareFaultsAlert =
                AlertsManager.create("Coral Holder sensor 1 hardware faults detected!", Alert.AlertType.kError);
        this.sensor2HardwareFaultsAlert =
                AlertsManager.create("Coral Holder sensor 2 hardware faults detected!", Alert.AlertType.kError);
    }

    public boolean hardwareOK() {
        return inputs.firstSensorConnected && inputs.secondSensorConnected && inputs.motorConnected;
    }

    private void setVoltage(double rollerVolts, double feederVolts) {
        if (!hardwareOK()) rollerVolts = feederVolts = 0;
        io.setRollerMotorOutput(rollerVolts);
        io.setCollectorMotorOutput(feederVolts);
    }

    @Override
    public void periodic() {
        // Update inputs from IO and AdvantageKit.
        io.updateInputs(inputs);
        Logger.processInputs("CoralHolder", inputs);

        // Update alerts
        motorHardwareFaultsAlert.set(!inputs.motorConnected);
        sensor1HardwareFaultsAlert.set(!inputs.firstSensorConnected);
        sensor2HardwareFaultsAlert.set(!inputs.secondSensorConnected);

        Logger.recordOutput("CoralHolder/Has Coral", hasCoral.getAsBoolean());
        Logger.recordOutput("CoralHolder/Coral In Place", coralInPlace.getAsBoolean());
        Logger.recordOutput("CoralHolder/First Sensor", firstSensor.getAsBoolean());
        Logger.recordOutput("CoralHolder/Second Sensor", secondSensor.getAsBoolean());

        visualizeCoral();
    }

    private void visualizeCoral() {
        String key = "CoralInRobot";
        Pose2d robotPose = robotPoseSupplier.get();
        double elevatorHeight = elevatorHeightSupplier.getAsDouble();
        Rotation2d armAngle = armAngleSupplier.get();
        if (coralInPlace.getAsBoolean())
            SuperStructureVisualizer.visualizeCoralInCoralHolder(
                    key, robotPose, elevatorHeight, armAngle, Centimeters.of(8));
        else if (firstSensor.getAsBoolean())
            SuperStructureVisualizer.visualizeCoralInCoralHolder(
                    key, robotPose, elevatorHeight, armAngle, Centimeters.zero());
        else if (secondSensor.getAsBoolean())
            SuperStructureVisualizer.visualizeCoralInCoralHolder(
                    key, robotPose, elevatorHeight, armAngle, Centimeters.of(15));
        else Logger.recordOutput(key, new Pose3d(0, 0, -1, new Rotation3d()));
    }

    /**
     * A sequence that intakes a Coral.
     *
     * <p>Will roll the coral in and move it to the holding position (where both sensor1 and sensor2 are triggered).
     */
    public Command intakeCoralSequence() {
        return Commands.sequence(
                        // Run the rollers forward quickly until the coral hits the first sensor
                        runVolts(VOLTAGE_SETTINGS.INTAKE_VOLTS(), 6.0).until(firstSensor),
                        // Run the rollers backwards for 0.1 for a rapid brake
                        runVolts(VOLTAGE_SETTINGS.BRAKE_VOLTS(), 2.0).withTimeout(0.1),
                        // Next, run the rollers forward slowly until the coal hits the second sensor
                        runVolts(VOLTAGE_SETTINGS.SHUFFLE_VOLTS(), 2.0).until(coralInPlace))
                // Only run when the rollers are not in place yet
                .onlyIf(coralInPlace.negate())
                // Stop the intake at the end of the command
                .finallyDo(() -> setVoltage(0.0, 0.0));
    }

    /**
     * A sequence that prepare to score level 4.
     *
     * <p>Will roll the coral in and move it to the holding position (where both sensor1 and sensor2 are triggered).
     */
    public Command prepareToScoreL4() {
        return Commands.sequence(
                        // Run the rollers forward quickly until the coral hits the first sensor
                        runVolts(-0.8, -0.8).until(() -> !coralInPlace.getAsBoolean()),
                        // Run the rollers backwards for 0.1 for a rapid brake
                        runVolts(-0.5, -0.5).withTimeout(0.6))
                // Stop the intake at the end of the command
                .finallyDo(() -> setVoltage(0.0, 0.0));
    }

    /**
     * Shuffles the coral such that.
     *
     * <p>This is used to move the coral to the appropriate position for scoring.
     */
    public Command shuffleCoralSequence() {
        return Commands.sequence(
                        // If the coral is not in place (triggering sensor 2) yet,
                        // we run rollers slowly forward until it triggers sensor 2.
                        runVolts(VOLTAGE_SETTINGS.SHUFFLE_VOLTS(), 2.0)
                                .onlyIf(secondSensor.negate())
                                .until(secondSensor),
                        // Next, run the rollers slowly backwards until it does not trigger sensor 2
                        runVolts(-VOLTAGE_SETTINGS.SHUFFLE_VOLTS(), 2.0).until(secondSensor.negate()))
                // Only shuffle the coral if we have a coral.
                .onlyIf(hasCoral)
                .withTimeout(1.5)
                // Stop the intake at the end of the command.
                .finallyDo(() -> setVoltage(0.0, 0.0));
    }

    public Command keepCoralShuffledForever() {
        return Commands.sequence(
                shuffleCoralSequence(),
                shuffleCoralSequence().onlyIf(secondSensor).repeatedly());
    }

    /** Score the Coral inside the holder. */
    public Command scoreCoral(double timeOutSeconds) {
        return runVolts(VOLTAGE_SETTINGS.SHOOT_VOLTS(), 1)
                .finallyDo(() -> setVoltage(0.0, 0.0))
                .withTimeout(timeOutSeconds);
    }

    public Command runVolts(double rollerVolts, double feederVolts) {
        return run(() -> setVoltage(rollerVolts, feederVolts));
    }

    public Command runIdle() {
        return runVolts(0, 0);
    }
}
