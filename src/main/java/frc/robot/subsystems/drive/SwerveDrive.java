// Original Source:
// https://github.com/Mechanical-Advantage/AdvantageKit/tree/main/example_projects/advanced_swerve_drive/src/main,
// Copyright 2021-2024 FRC 6328
// Modified by 6414 Voyager https://github.com/Robotics-Hubs/

package frc.robot.subsystems.drive;

import static edu.wpi.first.units.Units.*;
import static frc.robot.constants.DriveControlLoops.*;
import static frc.robot.constants.DriveTrainConstants.*;

import com.pathplanner.lib.path.PathConstraints;
import com.pathplanner.lib.util.DriveFeedforwards;
import com.pathplanner.lib.util.swerve.SwerveSetpoint;
import com.pathplanner.lib.util.swerve.SwerveSetpointGenerator;
import edu.wpi.first.math.filter.Debouncer;
import edu.wpi.first.math.geometry.*;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.units.measure.*;
import edu.wpi.first.wpilibj.Alert;
import edu.wpi.first.wpilibj.RobotController;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.*;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import frc.robot.Robot;
import frc.robot.RobotState;
import frc.robot.generated.TunerConstants;
import frc.robot.subsystems.drive.IO.*;
import frc.robot.utils.AlertsManager;
import frc.robot.utils.ChassisHeadingController;
import frc.robot.utils.CustomMaths.TipOverDetection;
import frc.robot.utils.FieldMirroringUtils;
import frc.robot.utils.RobotTimeUtils;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import org.littletonrobotics.junction.AutoLogOutput;
import org.littletonrobotics.junction.Logger;

public class SwerveDrive extends SubsystemBase implements HolonomicDriveSubsystem {
    public enum DriveType {
        GENERIC,
        CTRE_ON_RIO,
        CTRE_ON_CANIVORE
    }

    private final GyroIO gyroIO;
    private final GyroIOInputsAutoLogged gyroInputs;
    private final OdometryThreadInputsAutoLogged odometryThreadInputs;
    private final CanBusIO canBusIO;
    private final CanBusIO.CanBusInputs canBusInputs;
    private final SwerveModule[] swerveModules;

    private final OdometryThread odometryThread;

    // Alerts
    private final Alert gyroDisconnectedAlert =
            AlertsManager.create("Gyro hardware fault detected!", Alert.AlertType.kError);
    private final Alert gyroConfigurationFailed = AlertsManager.create(
            "Gyro configuration failed! Reboot robot after fixing connection.", Alert.AlertType.kError);
    private final Alert canBusHighUtilization =
            AlertsManager.create("Drivetrain CanBus high utilization!", Alert.AlertType.kError);
    private final Alert batteryBrownoutAlert =
            AlertsManager.create("Battery brownout detected!", Alert.AlertType.kError);
    private final Debouncer drivetrainOverCurrentDebouncer = new Debouncer(0.2, Debouncer.DebounceType.kBoth);
    private final Alert drivetrainOverCurrentAlert =
            AlertsManager.create("Drivetrain over current detected! Current: ", Alert.AlertType.kError);

    private final Debouncer batteryBrownoutDebouncer = new Debouncer(0.5, Debouncer.DebounceType.kFalling);
    private final Debouncer robotTippingDebouncer = new Debouncer(0.25, Debouncer.DebounceType.kBoth);

    private boolean robotTipping = false;
    private static final Angle TIP_OVER_THRESHOLD = Degrees.of(2.4);
    public Trigger driveTrainTipping = new Trigger(() -> robotTipping);

    private final SwerveSetpointGenerator setpointGenerator;
    private SwerveSetpoint setpoint;

    public SwerveDrive(
            DriveType type,
            GyroIO gyroIO,
            CanBusIO canBusIO,
            ModuleIO frontLeftModuleIO,
            ModuleIO frontRightModuleIO,
            ModuleIO backLeftModuleIO,
            ModuleIO backRightModuleIO) {
        super("Drive");
        this.gyroIO = gyroIO;
        this.canBusIO = canBusIO;
        this.canBusInputs = new CanBusIO.CanBusInputs();
        this.gyroInputs = new GyroIOInputsAutoLogged();
        this.swerveModules = new SwerveModule[] {
            new SwerveModule(frontLeftModuleIO, "FrontLeft"),
            new SwerveModule(frontRightModuleIO, "FrontRight"),
            new SwerveModule(backLeftModuleIO, "BackLeft"),
            new SwerveModule(backRightModuleIO, "BackRight"),
        };

        this.odometryThread = OdometryThread.createInstance(type);
        this.odometryThreadInputs = new OdometryThreadInputsAutoLogged();
        this.odometryThread.start();

        gyroDisconnectedAlert.set(false);
        batteryBrownoutAlert.set(false);
        drivetrainOverCurrentAlert.set(false);
        // Prevents fake alerts to show up dues to falling type debounce
        RobotTimeUtils.delay(0.5);

        setpointGenerator = new SwerveSetpointGenerator(defaultPathPlannerRobotConfig(), RPM.of(300));
        this.setpoint = new SwerveSetpoint(new ChassisSpeeds(), getModuleStates(), DriveFeedforwards.zeros(4));

        startDashboardDisplay();
    }

    @Override
    public void periodic() {
        fetchOdometryInputs();
        modulesPeriodic();

        for (int timeStampIndex = 0;
                timeStampIndex < odometryThreadInputs.odometryTicksCountInPreviousRobotPeriod;
                timeStampIndex++) feedSingleOdometryDataToPositionEstimator(timeStampIndex);

        RobotState.getInstance()
                .addChassisSpeedsObservation(
                        getModuleStates(),
                        gyroInputs.connected
                                ? OptionalDouble.of(gyroInputs.yawVelocityRadPerSec)
                                : OptionalDouble.empty());

        RobotState.getInstance()
                .addChassisSpeedsObservation(
                        getModuleStates(),
                        gyroInputs.connected
                                ? OptionalDouble.of(gyroInputs.yawVelocityRadPerSec)
                                : OptionalDouble.empty());

        RobotState.getInstance().updateAlerts();
        gyroConfigurationFailed.set(gyroInputs.configurationFailed);
        gyroDisconnectedAlert.set(!gyroInputs.configurationFailed && !gyroInputs.connected);
        canBusHighUtilization.setText(
                "Drivetrain CanBus high utilization: " + (int) (canBusInputs.utilization * 100) + "%");
        canBusHighUtilization.set(canBusInputs.utilization > 0.8);
        batteryBrownoutAlert.set(batteryBrownoutDebouncer.calculate(RobotController.isBrownedOut()));
        drivetrainOverCurrentAlert.set(drivetrainOverCurrentDebouncer.calculate(
                getDriveTrainTotalCurrentAmps() > OVER_CURRENT_WARNING.in(Amps)));

        Logger.recordOutput(
                "RobotState/SensorLessOdometryPose", RobotState.getInstance().getSensorLessOdometryPose());
        Logger.recordOutput(
                "RobotState/PrimaryEstimatorPose", RobotState.getInstance().getPrimaryEstimatorPose());
        Logger.recordOutput(
                "RobotState/PrimaryEstimatorPoseWith3dRot",
                new Pose3d(
                        new Translation3d(RobotState.getInstance()
                                .getPrimaryEstimatorPose()
                                .getTranslation()),
                        getDriveTrain3dOrientation()));
        Logger.recordOutput(
                "RobotState/VisionSensitivePose", RobotState.getInstance().getVisionPose());
        Logger.recordOutput(
                "RobotState/ControlLoopPose", RobotState.getInstance().getPose());
        Logger.recordOutput(
                "RobotState/ControlLoopPoseWithLookAhead",
                RobotState.getInstance().getPoseWithLookAhead());

        robotTipping = robotTippingDebouncer.calculate(
                TipOverDetection.getTippingAngleRad(getDriveTrain3dOrientation()) > TIP_OVER_THRESHOLD.in(Radians));
        Logger.recordOutput(
                "RobotState/TippingAngleDeg",
                Math.toDegrees(TipOverDetection.getTippingAngleRad(getDriveTrain3dOrientation())));
    }

    private void fetchOdometryInputs() {
        odometryThread.lockOdometry();
        odometryThread.updateInputs(odometryThreadInputs);
        Logger.processInputs("Drive/OdometryThread", odometryThreadInputs);

        for (var module : swerveModules) module.updateOdometryInputs();

        gyroIO.updateInputs(gyroInputs);
        Logger.processInputs("Drive/Gyro", gyroInputs);
        canBusIO.updateInputs(canBusInputs);
        Logger.processInputs("Drive/CANBus", canBusInputs);

        odometryThread.unlockOdometry();
    }

    private void modulesPeriodic() {
        for (var module : swerveModules) module.modulePeriodic();
    }

    private void feedSingleOdometryDataToPositionEstimator(int timeStampIndex) {
        final SwerveModulePosition[] modulePositions = getModulesPosition(timeStampIndex);

        RobotState.getInstance()
                .addOdometryObservation(new RobotState.OdometryObservation(
                        modulePositions,
                        gyroInputs.connected
                                ? Optional.of(gyroInputs.odometryYawPositions[timeStampIndex])
                                : Optional.empty(),
                        odometryThreadInputs.measurementTimeStamps[timeStampIndex]));
    }

    private SwerveModulePosition[] getModulesPosition(int timeStampIndex) {
        SwerveModulePosition[] swerveModulePositions = new SwerveModulePosition[swerveModules.length];
        for (int moduleIndex = 0; moduleIndex < 4; moduleIndex++)
            swerveModulePositions[moduleIndex] = swerveModules[moduleIndex].getOdometryPositions()[timeStampIndex];
        return swerveModulePositions;
    }

    @Override
    public void runRobotCentricChassisSpeeds(ChassisSpeeds speeds) {
        if (!ENABLE_SOFTWARE_CONSTRAIN) {
            runRobotCentricSpeedsWithFeedforwards(speeds, DriveFeedforwards.zeros(4));
            return;
        }

        PathConstraints constraints =
                getPathConstraints(RobotState.getInstance().lowSpeedModeEnabled());
        this.setpoint = setpointGenerator.generateSetpoint(setpoint, speeds, constraints, Robot.defaultPeriodSecs, 13);

        executeSetpoint();
    }

    private static PathConstraints getPathConstraints(boolean lowSpeedMode) {
        double accelerationConstrain = lowSpeedMode
                ? ACCELERATION_SOFT_CONSTRAIN_LOW.in(MetersPerSecondPerSecond)
                : ACCELERATION_SOFT_CONSTRAIN.in(MetersPerSecondPerSecond);
        double velocityConstrain = lowSpeedMode
                ? MOVEMENT_VELOCITY_SOFT_CONSTRAIN_LOW.in(MetersPerSecond)
                : MOVEMENT_VELOCITY_SOFT_CONSTRAIN.in(MetersPerSecond);
        double angularVelocityConstrain = lowSpeedMode
                ? ANGULAR_VELOCITY_SOFT_CONSTRAIN_LOW.in(RadiansPerSecond)
                : ANGULAR_VELOCITY_SOFT_CONSTRAIN.in(RadiansPerSecond);
        double angularAccelerationConstrain = lowSpeedMode
                ? ANGULAR_ACCELERATION_SOFT_CONSTRAIN_LOW.in(RadiansPerSecondPerSecond)
                : ANGULAR_ACCELERATION_SOFT_CONSTRAIN.in(RadiansPerSecondPerSecond);
        return new PathConstraints(
                velocityConstrain, accelerationConstrain, angularVelocityConstrain, angularAccelerationConstrain);
    }

    @Override
    public void runRobotCentricSpeedsWithFeedforwards(ChassisSpeeds speeds, DriveFeedforwards feedforwards) {
        this.setpoint = new SwerveSetpoint(speeds, getModuleStates(), feedforwards);
        executeSetpoint();
    }

    private void executeSetpoint() {
        OptionalDouble angularVelocityOverride =
                ChassisHeadingController.getInstance().calculate(getMeasuredChassisSpeedsFieldRelative(), getPose());
        ChassisSpeeds speeds = setpoint.robotRelativeSpeeds();

        if (angularVelocityOverride.isPresent()) {
            speeds = new ChassisSpeeds(
                    speeds.vxMetersPerSecond, speeds.vyMetersPerSecond, angularVelocityOverride.getAsDouble());
            speeds = ChassisSpeeds.discretize(speeds, Robot.defaultPeriodSecs);
        }

        SwerveModuleState[] setPointStates = DRIVE_KINEMATICS.toSwerveModuleStates(speeds);
        SwerveDriveKinematics.desaturateWheelSpeeds(setPointStates, CHASSIS_MAX_VELOCITY);

        // Send setpoints to modules
        SwerveModuleState[] optimizedSetpointStates = new SwerveModuleState[4];
        for (int i = 0; i < 4; i++)
            optimizedSetpointStates[i] = swerveModules[i].runSetPoint(
                    setPointStates[i],
                    setpoint.feedforwards().robotRelativeForcesX()[i],
                    setpoint.feedforwards().robotRelativeForcesY()[i]);

        Logger.recordOutput("SwerveStates/Setpoints", setPointStates);
        Logger.recordOutput("SwerveStates/SetpointsOptimized", optimizedSetpointStates);
    }

    @Override
    public void stop() {
        Rotation2d[] swerveHeadings = new Rotation2d[swerveModules.length];
        for (int i = 0; i < swerveHeadings.length; i++) swerveHeadings[i] = new Rotation2d();
        DRIVE_KINEMATICS.resetHeadings(swerveHeadings);
        HolonomicDriveSubsystem.super.stop();
    }

    /**
     * Locks the chassis and turns the modules to an X formation to resist movement. The lock will be cancelled the next
     * time a nonzero velocity is requested.
     */
    public Command lockChassisWithXFormation() {
        Rotation2d[] swerveHeadings = new Rotation2d[swerveModules.length];
        for (int i = 0; i < swerveHeadings.length; i++) swerveHeadings[i] = MODULE_TRANSLATIONS[i].getAngle();
        return new FunctionalCommand(
                () -> DRIVE_KINEMATICS.resetHeadings(swerveHeadings),
                () -> {
                    for (int i = 0; i < swerveModules.length; i++)
                        swerveModules[i].forceRunSetPoint(
                                new SwerveModuleState(0, swerveHeadings[i]), Newtons.zero(), Newtons.zero());
                },
                (interrupted) -> {},
                () -> false,
                this);
    }

    /** Turns the motor brakes on */
    public void setMotorBrake(boolean motorBrakeEnabled) {
        for (SwerveModule module : swerveModules) module.setMotorBrake(motorBrakeEnabled);
    }

    /** Returns the module states (turn angles and drive velocities) for all the modules. */
    @AutoLogOutput(key = "SwerveStates/Measured")
    private SwerveModuleState[] getModuleStates() {
        SwerveModuleState[] states = new SwerveModuleState[swerveModules.length];
        for (int i = 0; i < states.length; i++) states[i] = swerveModules[i].getMeasuredState();
        return states;
    }

    /** Returns the module positions (turn angles and drive positions) for all the modules. */
    private SwerveModulePosition[] getModuleLatestPositions() {
        SwerveModulePosition[] states = new SwerveModulePosition[swerveModules.length];
        for (int i = 0; i < states.length; i++) states[i] = swerveModules[i].getLatestPosition();
        return states;
    }

    @Override
    // Pose is for PID control
    public Pose2d getPose() {
        return RobotState.getInstance().getPoseWithLookAhead();
    }

    @Override
    public void setPose(Pose2d pose) {
        RobotState.getInstance().resetPose(pose);
    }

    @Override
    public ChassisSpeeds getMeasuredChassisSpeedsRobotRelative() {
        return RobotState.getInstance().getRobotRelativeSpeeds();
    }

    @Override
    public double getChassisMaxLinearVelocityMetersPerSec() {
        return CHASSIS_MAX_VELOCITY.in(MetersPerSecond);
    }

    @Override
    public double getChassisMaxAccelerationMetersPerSecSq() {
        return CHASSIS_MAX_ACCELERATION.in(MetersPerSecondPerSecond);
    }

    @Override
    public double getChassisMaxAngularVelocity() {
        return CHASSIS_MAX_ANGULAR_VELOCITY.in(RadiansPerSecond);
    }

    @Override
    public double getChassisMaxAngularAccelerationRadPerSecSq() {
        return CHASSIS_MAX_ANGULAR_ACCELERATION.in(RadiansPerSecondPerSecond);
    }

    private void startDashboardDisplay() {
        SmartDashboard.putData("Swerve Drive", builder -> {
            builder.setSmartDashboardType("SwerveDrive");

            builder.addDoubleProperty(
                    "Front Left Angle", () -> swerveModules[0].getSteerFacing().getRadians(), null);
            builder.addDoubleProperty(
                    "Front Left Velocity", () -> swerveModules[0].getDriveVelocityMetersPerSec(), null);

            builder.addDoubleProperty(
                    "Front Right Angle", () -> swerveModules[0].getSteerFacing().getRadians(), null);
            builder.addDoubleProperty(
                    "Front Right Velocity", () -> swerveModules[0].getDriveVelocityMetersPerSec(), null);

            builder.addDoubleProperty(
                    "Back Left Angle", () -> swerveModules[0].getSteerFacing().getRadians(), null);
            builder.addDoubleProperty(
                    "Back Left Velocity", () -> swerveModules[0].getDriveVelocityMetersPerSec(), null);

            builder.addDoubleProperty(
                    "Back Right Angle", () -> swerveModules[0].getSteerFacing().getRadians(), null);
            builder.addDoubleProperty(
                    "Back Right Velocity", () -> swerveModules[0].getDriveVelocityMetersPerSec(), null);

            builder.addDoubleProperty(
                    "Robot Angle",
                    () -> getFacing()
                            .minus(FieldMirroringUtils.getCurrentAllianceDriverStationFacing())
                            .getRadians(),
                    null);
        });
    }

    public double getCanBusUtilization() {
        return canBusInputs.utilization;
    }

    public Rotation3d getDriveTrain3dOrientation() {
        return new Rotation3d(
                gyroInputs.rollRad, gyroInputs.pitchRad, getFacing().getRadians());
    }

    @AutoLogOutput(key = "DrivetrainTotalCurrentAmps")
    public double getDriveTrainTotalCurrentAmps() {
        return Arrays.stream(swerveModules)
                .mapToDouble(SwerveModule::getTotalSupplyCurrentAmps)
                .sum();
    }

    private void runCharacterization(Voltage voltage) {
        SwerveModuleState[] moduleStates = DRIVE_KINEMATICS.toSwerveModuleStates(new ChassisSpeeds(0, 0, 1));
        for (int i = 0; i < swerveModules.length; i++)
            switch (TunerConstants.FrontLeft.DriveMotorClosedLoopOutput) {
                case Voltage -> swerveModules[i].runVoltageCharacterization(moduleStates[i].angle, voltage);
                case TorqueCurrentFOC -> swerveModules[i].runCurrentCharacterization(
                        moduleStates[i].angle, Amps.of(voltage.in(Volts)));
            }
    }

    private final SysIdRoutine sysId = new SysIdRoutine(
            new SysIdRoutine.Config(
                    null, null, null, (state) -> Logger.recordOutput("Drive/SysIdState", state.toString())),
            new SysIdRoutine.Mechanism(this::runCharacterization, null, this));
    /*
     * To run characterization for swerve:
     *   1. Select SysId command on dashboard and run robot test.
     *   2. Export the log file using AdvantageScope, (Format: "WPILOG", Timestamps:"AdvantageKit Cycles", Prefixes:"Drive/,RealOutputs/Drive/SysIdState").
     *   3. Open the exported log file with Wpilib SysId Tool, select the data:
     *       - Test State: RealOutputs/Drive/SysIdState
     *       - Velocity: Drive/Module-MODULE_NAME/DriveWheelFinalVelocityRevolutionsPerSecond
     *       - Position: Drive/Module-MODULE_NAME/DriveWheelFinalRevolutions
     *       - Voltage: Drive/Module-MODULE_NAME/DriveAppliedVolts
     *   4. Calculate the gains for ALL FOUR modules, take the average.
     *       - Note that if the difference between modules are too big, SOMETHING IS WRONG.
     *   4. The calculated kS is correct; but kV NEEDS TO BE DIVIDED BY GEAR RATIO.
     *   5. Don't use the calculated kP, tune the kP manually.
     * */

    public Command sysIdQuasistatic(SysIdRoutine.Direction direction) {
        return sysId.quasistatic(direction);
    }

    public Command sysIdDynamic(SysIdRoutine.Direction direction) {
        return sysId.dynamic(direction);
    }
}
