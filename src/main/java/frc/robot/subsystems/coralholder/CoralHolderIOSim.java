package frc.robot.subsystems.coralholder;

import static edu.wpi.first.units.Units.*;
import static frc.robot.subsystems.coralholder.CoralHolderConstants.*;

import edu.wpi.first.math.geometry.*;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.XboxController;
import frc.robot.Robot;
import frc.robot.simulation.SimulatedArena;
import frc.robot.simulation.drivesims.AbstractDriveTrainSimulation;
import frc.robot.simulation.gamepieces.GamePieceProjectile;
import frc.robot.simulation.seasonspecific.reefscape2025.ReefscapeCoralOnFly;
import frc.robot.subsystems.superstructure.SuperStructureVisualizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.littletonrobotics.junction.Logger;

public class CoralHolderIOSim implements CoralHolderIO {
    private final AbstractDriveTrainSimulation driveSimulation;
    private final Supplier<Rotation2d> armAngleSupplier;
    private final DoubleSupplier elevatorHeightSupplier;

    private boolean hasCoral;
    /** 0-1 Coral is on feeder 1-2 Coral is on roller */
    private double coralPosition;

    public CoralHolderIOSim(
            AbstractDriveTrainSimulation driveSimulation,
            Supplier<Rotation2d> armAngleSupplier,
            DoubleSupplier elevatorHeightSupplier) {
        this.driveSimulation = driveSimulation;
        this.armAngleSupplier = armAngleSupplier;
        this.elevatorHeightSupplier = elevatorHeightSupplier;

        // preload
        this.hasCoral = true;
        coralPosition = 1.5;
    }

    XboxController controller = new XboxController(0);

    @Override
    public void updateInputs(CoralHolderInputs inputs) {
        inputs.motorConnected = inputs.firstSensorConnected =
                inputs.secondSensorConnected = inputs.firstSensorReadingValid = inputs.secondSensorReadingValid = true;

        boolean hasNewCoral = controller.getBackButton() || DriverStation.isAutonomous();
        if (!hasCoral && rollerMotorVolts == VOLTAGE_SETTINGS.INTAKE_VOLTS()) hasCoral = hasNewCoral;

        if (hasCoral) simulateCoralInsideIntake();

        boolean firstSensorTriggered = hasCoral && coralPosition >= 1 && coralPosition < 1.7;
        boolean secondSensorTriggered = hasCoral && coralPosition > 1.3;
        inputs.firstSensorDistanceMM = firstSensorTriggered ? 0 : 9999;
        inputs.secondSensorDistanceMM = secondSensorTriggered ? 0 : 9999;

        inputs.rollerMotorOutputVolts = rollerMotorVolts;
        inputs.feederMotorOutputVolts = collectorMotorOutput;
        if (coralPosition >= 2) launchCoral();
        Logger.recordOutput("Sim/coralPosition", coralPosition);
        Logger.recordOutput("Sim/hasCoral", hasCoral);
    }

    private boolean hasNewCoralFromCollector() {
        // find all corals
        List<ReefscapeCoralOnFly> corals = new ArrayList<>();
        for (GamePieceProjectile gamePieceProjectile :
                SimulatedArena.getInstance().gamePieceLaunched())
            if (gamePieceProjectile instanceof ReefscapeCoralOnFly coral) corals.add(coral);

        // choose those close enough to intake
        for (ReefscapeCoralOnFly coral : corals)
            if (insideIntakeRange(driveSimulation.getSimulatedDriveTrainPose(), coral.getPose3d()))
                return SimulatedArena.getInstance().removeProjectile(coral);

        return false;
    }

    private void simulateCoralInsideIntake() {
        if (DriverStation.isDisabled()) rollerMotorVolts = collectorMotorOutput = 0.0;
        if (coralPosition < 1) {
            coralPosition += collectorMotorOutput / 6.0 / COLLECTOR_TIME_SECONDS_AT_6V * Robot.defaultPeriodSecs;
        } else {
            coralPosition += rollerMotorVolts / 6.0 / ROLLER_TIME_SECONDS_AT_6V * Robot.defaultPeriodSecs;
            if (coralPosition < 1) coralPosition = 1;
        }
    }

    private void launchCoral() {
        Pose2d robotPose = driveSimulation.getSimulatedDriveTrainPose();
        Pose3d coralInitialPose = SuperStructureVisualizer.getCoralPositionRobotRelative(
                elevatorHeightSupplier.getAsDouble(), armAngleSupplier.get(), Centimeters.of(10));
        ReefscapeCoralOnFly coralOnFly = new ReefscapeCoralOnFly(
                robotPose.getTranslation(),
                coralInitialPose.getTranslation().toTranslation2d(),
                driveSimulation.getDriveTrainSimulatedChassisSpeedsFieldRelative(),
                robotPose.getRotation(),
                coralInitialPose.getTranslation().getMeasureZ(),
                CORAL_LAUNCHING_VELOCITY_6V.times(rollerMotorVolts / 6.0),
                coralInitialPose.getRotation().getMeasureY().times(-1));
        SimulatedArena.getInstance().addGamePieceProjectile(coralOnFly);
        hasCoral = false;
        coralPosition = 0;
    }

    private static boolean insideIntakeRange(Pose2d simulatedDriveTrainPose, Pose3d coralPositionInAir) {
        Translation3d robotPositionOnField = new Translation3d(simulatedDriveTrainPose.getTranslation());
        Rotation3d robotOrientation = new Rotation3d(simulatedDriveTrainPose.getRotation());
        Translation3d intakePositionOnField =
                robotPositionOnField.plus(COLLECTOR_POSITION_ON_ROBOT.rotateBy(robotOrientation));

        Translation3d difference = coralPositionInAir.getTranslation().minus(intakePositionOnField);
        return Math.abs(difference.getX()) < COLLECTOR_RANGE.getX()
                && Math.abs(difference.getY()) < COLLECTOR_RANGE.getY()
                && Math.abs(difference.getZ()) < COLLECTOR_RANGE.getZ();
    }

    double rollerMotorVolts = 0.0;

    @Override
    public void setRollerMotorOutput(double volts) {
        rollerMotorVolts = volts;
    }

    double collectorMotorOutput = 0.0;

    @Override
    public void setCollectorMotorOutput(double volts) {
        collectorMotorOutput = volts;
    }
}
