// Original Source:
// https://github.com/Mechanical-Advantage/AdvantageKit/tree/main/example_projects/advanced_swerve_drive/src/main,
// Copyright 2021-2024 FRC 6328
// Modified by 6414 Voyager https://github.com/Robotics-Hubs/

package frc.robot;

import static edu.wpi.first.units.Units.*;

import com.pathplanner.lib.auto.NamedCommands;
import com.pathplanner.lib.commands.PathPlannerAuto;
import edu.wpi.first.math.geometry.*;
import edu.wpi.first.wpilibj.*;
import edu.wpi.first.wpilibj.smartdashboard.Field2d;
import edu.wpi.first.wpilibj.smartdashboard.SendableChooser;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj.util.Color;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import edu.wpi.first.wpilibj2.command.button.JoystickButton;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import frc.robot.autos.*;
import frc.robot.commands.drive.*;
import frc.robot.commands.reefscape.ReefAlignment;
import frc.robot.constants.*;
import frc.robot.generated.TunerConstants;
import frc.robot.simulation.SimulatedArena;
import frc.robot.simulation.drivesims.SwerveDriveSimulation;
import frc.robot.simulation.drivesims.configs.DriveTrainSimulationConfig;
import frc.robot.simulation.drivesims.configs.SwerveModuleSimulationConfig;
import frc.robot.subsystems.coralholder.CoralHolder;
import frc.robot.subsystems.coralholder.CoralHolderIOReal;
import frc.robot.subsystems.coralholder.CoralHolderIOSim;
import frc.robot.subsystems.drive.*;
import frc.robot.subsystems.drive.IO.*;
import frc.robot.subsystems.led.LEDAnimation;
import frc.robot.subsystems.led.LEDStatusLight;
import frc.robot.subsystems.superstructure.SuperStructure;
import frc.robot.subsystems.superstructure.SuperStructureVisualizer;
import frc.robot.subsystems.superstructure.arm.Arm;
import frc.robot.subsystems.superstructure.arm.ArmIOReal;
import frc.robot.subsystems.superstructure.arm.ArmIOSim;
import frc.robot.subsystems.superstructure.elevator.Elevator;
import frc.robot.subsystems.superstructure.elevator.ElevatorIOReal;
import frc.robot.subsystems.superstructure.elevator.ElevatorIOSim;
import frc.robot.subsystems.vision.apriltags.AprilTagVision;
import frc.robot.subsystems.vision.apriltags.AprilTagVisionIOReal;
import frc.robot.subsystems.vision.apriltags.ApriltagVisionIOSim;
import frc.robot.subsystems.vision.apriltags.PhotonCameraProperties;
import frc.robot.utils.AlertsManager;
import frc.robot.utils.FieldMirroringUtils;
import frc.robot.utils.RobotJoystickDriveInput;
import frc.robot.utils.mathutils.RobotCommonMath;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.inputs.LoggedPowerDistribution;
import org.littletonrobotics.junction.networktables.LoggedDashboardChooser;

/**
 * This class is where the bulk of the robot should be declared. Since Command-based is a "declarative" paradigm, very
 * little robot logic should actually be handled in the {@link Robot} periodic methods (other than the scheduler calls).
 * Instead, the structure of the robot (including subsystems, commands, and button mappings) should be declared here.
 */
public class RobotContainer {
    public static final boolean SIMULATE_AUTO_PLACEMENT_INACCURACY = true;

    // pdp for akit logging
    public final LoggedPowerDistribution powerDistribution;
    // Subsystems
    public final SwerveDrive drive;
    public final AprilTagVision aprilTagVision;
    public final LEDStatusLight ledStatusLight;

    // Controller
    // public final DriverMap driver = new DriverMap.LeftHandedPS5(0);
    //    public final DriverMap driver = new DriverMap.LeftHandedXbox(0);
    //    public final CommandXboxController operator = new CommandXboxController(1);

    public final DriverMap driver = new DriverMap.CommandPilotController(1);
    public final CommandXboxController operator = new CommandXboxController(0);

    private final LoggedDashboardChooser<Auto> autoChooser;
    private final SendableChooser<Supplier<Command>> testChooser;

    // Simulated drive
    private final SwerveDriveSimulation driveSimulation;
    public final Arm arm;
    public final Elevator elevator;
    public final SuperStructure superStructure;
    public final CoralHolder coralHolder;

    private final Field2d field = new Field2d();

    public final Trigger isAlgaeMode;

    /** The container for the robot. Contains subsystems, OI devices, and commands. */
    public RobotContainer() {
        final List<PhotonCameraProperties> camerasProperties =
                VisionConstants.photonVisionCameras; // load configs stored directly in VisionConstants.java

        switch (Robot.CURRENT_ROBOT_MODE) {
            case REAL -> {
                // Real robot, instantiate hardware IO implementations
                driveSimulation = null;

                powerDistribution = LoggedPowerDistribution.getInstance(0, PowerDistribution.ModuleType.kRev);

                /* CTRE Chassis: */
                drive = new SwerveDrive(
                        Objects.equals(TunerConstants.kCANBus.getName(), "rio")
                                ? SwerveDrive.DriveType.CTRE_ON_RIO
                                : SwerveDrive.DriveType.CTRE_ON_CANIVORE,
                        new GyroIOPigeon2(TunerConstants.DrivetrainConstants),
                        new CanBusIOReal(TunerConstants.kCANBus),
                        new ModuleIOTalon(TunerConstants.FrontLeft, "FrontLeft"),
                        new ModuleIOTalon(TunerConstants.FrontRight, "FrontRight"),
                        new ModuleIOTalon(TunerConstants.BackLeft, "BackLeft"),
                        new ModuleIOTalon(TunerConstants.BackRight, "BackRight"));

                aprilTagVision = new AprilTagVision(new AprilTagVisionIOReal(camerasProperties), camerasProperties);

                arm = new Arm(new ArmIOReal());
                elevator = new Elevator(new ElevatorIOReal());
                coralHolder = new CoralHolder(
                        new CoralHolderIOReal(),
                        RobotState.getInstance()::getPrimaryEstimatorPose,
                        arm::getArmAngle,
                        elevator::getHeightMeters);
            }

            case SIM -> {
                SimulatedArena.overrideSimulationTimings(
                        Seconds.of(Robot.defaultPeriodSecs), DriveTrainConstants.SIMULATION_TICKS_IN_1_PERIOD);
                this.driveSimulation = new SwerveDriveSimulation(
                        DriveTrainSimulationConfig.Default()
                                .withRobotMass(DriveTrainConstants.ROBOT_MASS)
                                .withBumperSize(DriveTrainConstants.BUMPER_LENGTH, DriveTrainConstants.BUMPER_WIDTH)
                                .withTrackLengthTrackWidth(
                                        DriveTrainConstants.TRACK_LENGTH, DriveTrainConstants.TRACK_WIDTH)
                                .withSwerveModule(new SwerveModuleSimulationConfig(
                                        DriveTrainConstants.DRIVE_MOTOR_MODEL,
                                        DriveTrainConstants.STEER_MOTOR_MODEL,
                                        DriveTrainConstants.DRIVE_GEAR_RATIO,
                                        DriveTrainConstants.STEER_GEAR_RATIO,
                                        DriveTrainConstants.DRIVE_FRICTION_VOLTAGE,
                                        DriveTrainConstants.STEER_FRICTION_VOLTAGE,
                                        DriveTrainConstants.WHEEL_RADIUS,
                                        DriveTrainConstants.STEER_INERTIA,
                                        DriveTrainConstants.WHEEL_COEFFICIENT_OF_FRICTION))
                                .withGyro(DriveTrainConstants.gyroSimulationFactory),
                        new Pose2d(3, 3, new Rotation2d()));
                SimulatedArena.getInstance().addDriveTrainSimulation(driveSimulation);

                powerDistribution = LoggedPowerDistribution.getInstance();
                // Sim robot, instantiate physics sim IO implementations
                final ModuleIOSim frontLeft = new ModuleIOSim(driveSimulation.getModules()[0]),
                        frontRight = new ModuleIOSim(driveSimulation.getModules()[1]),
                        backLeft = new ModuleIOSim(driveSimulation.getModules()[2]),
                        backRight = new ModuleIOSim(driveSimulation.getModules()[3]);
                final GyroIOSim gyroIOSim = new GyroIOSim(driveSimulation.getGyroSimulation());
                drive = new SwerveDrive(
                        SwerveDrive.DriveType.GENERIC,
                        gyroIOSim,
                        (canBusInputs) -> {},
                        frontLeft,
                        frontRight,
                        backLeft,
                        backRight);

                aprilTagVision = new AprilTagVision(
                        new ApriltagVisionIOSim(
                                camerasProperties,
                                VisionConstants.fieldLayout,
                                driveSimulation::getSimulatedDriveTrainPose),
                        camerasProperties);

                SimulatedArena.getInstance().resetFieldForAuto();

                arm = new Arm(new ArmIOSim());
                elevator = new Elevator(new ElevatorIOSim());
                coralHolder = new CoralHolder(
                        new CoralHolderIOSim(driveSimulation, arm::getArmAngle, elevator::getHeightMeters),
                        driveSimulation::getSimulatedDriveTrainPose,
                        arm::getArmAngle,
                        elevator::getHeightMeters);
            }

            default -> {
                this.driveSimulation = null;

                powerDistribution = LoggedPowerDistribution.getInstance();
                // Replayed robot, disable IO implementations
                drive = new SwerveDrive(
                        SwerveDrive.DriveType.GENERIC,
                        (canBusInputs) -> {},
                        (inputs) -> {},
                        (inputs) -> {},
                        (inputs) -> {},
                        (inputs) -> {},
                        (inputs) -> {});

                aprilTagVision = new AprilTagVision((inputs) -> {}, camerasProperties);

                arm = new Arm(armInputs -> {});
                elevator = new Elevator(elevatorInputs -> {});
                coralHolder = new CoralHolder(
                        coralHolderInputs -> {},
                        RobotState.getInstance()::getPrimaryEstimatorPose,
                        arm::getArmAngle,
                        elevator::getHeightMeters);
            }
        }

        this.superStructure = new SuperStructure(elevator, arm);
        this.ledStatusLight = new LEDStatusLight(0, 63, true, false);

        this.drive.configHolonomicPathPlannerAutoBuilder(field);

        SmartDashboard.putData("Select Test", testChooser = buildTestsChooser());
        autoChooser = buildAutoChooser();

        isAlgaeMode = new Trigger(() -> superStructure.currentPose() == SuperStructure.SuperStructurePose.LOW_ALGAE
                || superStructure.currentPose() == SuperStructure.SuperStructurePose.HIGH_ALGAE
                || superStructure.currentPose() == SuperStructure.SuperStructurePose.ALGAE_SWAP_2
                || superStructure.currentPose() == SuperStructure.SuperStructurePose.SCORE_ALGAE
                || superStructure.targetPose() == SuperStructure.SuperStructurePose.LOW_ALGAE
                || superStructure.targetPose() == SuperStructure.SuperStructurePose.HIGH_ALGAE
                || superStructure.targetPose() == SuperStructure.SuperStructurePose.ALGAE_SWAP_2
                || superStructure.targetPose() == SuperStructure.SuperStructurePose.SCORE_ALGAE);
        configureButtonBindings();
        configureAutoNamedCommands();
        configureLEDEffects();

        SmartDashboard.putData("Field", field);

        setMotorBrake(true);
    }

    private void configureAutoNamedCommands() {
        NamedCommands.registerCommand(
                "Raise Elevator",
                superStructure
                        .moveToPose(SuperStructure.SuperStructurePose.SCORE_L4)
                        .deadlineFor(Commands.waitSeconds(0.3).andThen(coralHolder.keepCoralShuffledForever()))
                        // only raise elevator if coral in place to avoid getting jammed
                        .onlyIf(coralHolder.secondSensor)
                        .asProxy());
    }

    private void configureAutoTriggers(PathPlannerAuto pathPlannerAuto) {}

    private LoggedDashboardChooser<Auto> buildAutoChooser() {
        final LoggedDashboardChooser<Auto> autoSendableChooser = new LoggedDashboardChooser<>("Select Auto");
        autoSendableChooser.addDefaultOption("None", Auto.none());
        autoSendableChooser.addOption("[Three Coral - Short] <-- LEFT SIDE <-- ", new ThreeCoralShort(false));
        autoSendableChooser.addOption("[Three Coral - Short] --> RIGHT SIDE -->", new ThreeCoralShort(true));

        SmartDashboard.putData("Select Auto", autoSendableChooser.getSendableChooser());
        return autoSendableChooser;
    }

    private SendableChooser<Supplier<Command>> buildTestsChooser() {
        final SendableChooser<Supplier<Command>> testsChooser = new SendableChooser<>();
        testsChooser.setDefaultOption("None", Commands::none);
        testsChooser.addOption(
                "Drive SysId- Quasistatic - Forward", () -> drive.sysIdQuasistatic(SysIdRoutine.Direction.kForward));
        testsChooser.addOption(
                "Drive SysId- Quasistatic - Reverse", () -> drive.sysIdQuasistatic(SysIdRoutine.Direction.kReverse));
        testsChooser.addOption(
                "Drive SysId- Dynamic - Forward", () -> drive.sysIdDynamic(SysIdRoutine.Direction.kForward));
        testsChooser.addOption(
                "Drive SysId- Dynamic - Reverse", () -> drive.sysIdDynamic(SysIdRoutine.Direction.kReverse));
        return testsChooser;
    }

    private boolean isDSPresentedAsRed = FieldMirroringUtils.isSidePresentedAsRed();
    private Command autonomousCommand = Commands.none();
    private Auto previouslySelectedAuto = null;
    /** reconfigures button bindings if alliance station has changed re-create autos if not yet created */
    public void checkForCommandChanges() {
        final Auto selectedAuto = autoChooser.get();
        if (FieldMirroringUtils.isSidePresentedAsRed() == isDSPresentedAsRed & selectedAuto == previouslySelectedAuto)
            return;

        try {
            this.autonomousCommand = selectedAuto.getAutoCommand(this);
            configureAutoTriggers(new PathPlannerAuto(autonomousCommand, selectedAuto.getStartingPoseAtBlueAlliance()));
        } catch (Exception e) {
            this.autonomousCommand = Commands.none();
            DriverStation.reportError(
                    "Error Occurred while obtaining autonomous command: \n"
                            + e.getMessage()
                            + "\n"
                            + Arrays.toString(e.getStackTrace()),
                    false);
            throw new RuntimeException(e);
        }
        resetFieldAndOdometryForAuto(selectedAuto.getStartingPoseAtBlueAlliance());

        previouslySelectedAuto = selectedAuto;
        isDSPresentedAsRed = FieldMirroringUtils.isSidePresentedAsRed();
    }

    private void resetFieldAndOdometryForAuto(Pose2d robotStartingPoseAtBlueAlliance) {
        final Pose2d startingPose = FieldMirroringUtils.toCurrentAlliancePose(robotStartingPoseAtBlueAlliance);

        if (driveSimulation != null) {
            Transform2d placementError = SIMULATE_AUTO_PLACEMENT_INACCURACY
                    ? new Transform2d(
                            RobotCommonMath.generateRandomNormal(0, 0.2),
                            RobotCommonMath.generateRandomNormal(0, 0.2),
                            Rotation2d.fromDegrees(RobotCommonMath.generateRandomNormal(0, 1)))
                    : new Transform2d();
            driveSimulation.setSimulationWorldPose(startingPose.plus(placementError));
            SimulatedArena.getInstance().resetFieldForAuto();
        }

        aprilTagVision
                .focusOnTarget(-1, -1)
                .withTimeout(0.1)
                .alongWith(Commands.runOnce(() -> drive.setPose(startingPose), drive))
                .ignoringDisable(true)
                .schedule();
    }

    public Command scoreCoral(double scoringTimeOut) {
        Command retrieveElevator = superStructure.moveToPose(SuperStructure.SuperStructurePose.IDLE);
        return Commands.deferredProxy(() -> superStructure
                        .moveToPose(SuperStructure.SuperStructurePose.SCORE_L4_COMPLETE)
                        .onlyIf(() -> superStructure.targetPose() == SuperStructure.SuperStructurePose.SCORE_L4)
                        .beforeStarting(Commands.waitSeconds(0.1))
                        .alongWith(coralHolder.scoreCoral(scoringTimeOut)))
                .finallyDo(retrieveElevator::schedule);
    }

    /**
     * Use this method to define your button->command mappings. Buttons can be created by instantiating a
     * {@link GenericHID} or one of its subclasses ({@link Joystick} or {@link XboxController}), and then passing it to
     * a {@link JoystickButton}.
     */
    public void configureButtonBindings() {
        SmartDashboard.putData(
                "Enable Motor Brake",
                Commands.runOnce(() -> setMotorBrake(true)).ignoringDisable(true));
        SmartDashboard.putData(
                "Disable Motor Brake",
                Commands.runOnce(() -> setMotorBrake(false)).ignoringDisable(true));

        /* joystick drive command */
        final RobotJoystickDriveInput driveInput = driver.getDriveInput();
        IntSupplier pov =
                // driver.getController().getHID()::getPOV;
                () -> -1;
        final JoystickDrive joystickDrive = new JoystickDrive(driveInput, () -> true, pov, drive);
        drive.setDefaultCommand(joystickDrive);
        JoystickDrive.instance = Optional.of(joystickDrive);

        /* reset gyro heading manually (in case the vision does not work) */
        operator.start()
                .onTrue(Commands.runOnce(
                                () -> drive.setPose(new Pose2d(
                                        drive.getPose().getTranslation(),
                                        FieldMirroringUtils.getCurrentAllianceDriverStationFacing())),
                                drive)
                        .ignoringDisable(true));

        /* lock chassis with x-formation */
        driver.lockChassisWithXFormatButton().whileTrue(drive.lockChassisWithXFormation());

        coralHolder.setDefaultCommand(coralHolder.runIdle());

        Command flashLEDForIntake =
                ledStatusLight.playAnimationPeriodically(new LEDAnimation.Charging(Color.kPurple), 4);

        // intake
        operator.leftTrigger(0.5)
                .whileTrue(Commands.sequence(
                                superStructure.moveToPose(SuperStructure.SuperStructurePose.INTAKE),
                                coralHolder.intakeCoralSequence().beforeStarting(flashLEDForIntake::schedule))
                        .finallyDo(flashLEDForIntake::cancel))
                // move coral in place before retrieving arm
                .onFalse(coralHolder
                        .intakeCoralSequence()
                        .onlyIf(coralHolder.hasCoral)
                        .andThen(superStructure.moveToPose(SuperStructure.SuperStructurePose.IDLE)));
        operator.a()
                .onTrue(Commands.sequence(
                        arm.moveToPosition(SuperStructure.SuperStructurePose.RUN_UP.armAngle)
                                .onlyIf(() -> elevator.getHeightMeters() > 0.9),
                        elevator.moveToPosition(SuperStructure.SuperStructurePose.TRANSFER.elevatorHeightMeters)
                                .onlyIf(() -> elevator.getHeightMeters() > 0.39),
                        superStructure.moveToPose(SuperStructure.SuperStructurePose.IDLE)));
        operator.x()
                .onTrue(Commands.sequence(
                        arm.moveToPosition(SuperStructure.SuperStructurePose.RUN_UP.armAngle)
                                .onlyIf(() -> elevator.getHeightMeters() > 1.0),
                        arm.moveToPosition(SuperStructure.SuperStructurePose.SCORE_L2.armAngle)
                                .onlyIf(() -> elevator.getHeightMeters() <= 1.0 && elevator.getHeightMeters() >= 0.39),
                        superStructure.moveToPose(SuperStructure.SuperStructurePose.SCORE_L2)))
                .onTrue(coralHolder.keepCoralShuffledForever());
        operator.y()
                .onTrue(Commands.sequence(
                        arm.moveToPosition(SuperStructure.SuperStructurePose.RUN_UP.armAngle)
                                .onlyIf(() -> elevator.getHeightMeters() > 1.0),
                        arm.moveToPosition(SuperStructure.SuperStructurePose.SCORE_L3.armAngle)
                                .onlyIf(() -> elevator.getHeightMeters() <= 0.39),
                        superStructure.moveToPose(SuperStructure.SuperStructurePose.SCORE_L3)))
                .onTrue(coralHolder.keepCoralShuffledForever());
        operator.b()
                .onTrue(Commands.sequence(
                        arm.moveToPosition(SuperStructure.SuperStructurePose.SCORE_L4.armAngle),
                        superStructure.moveToPose(SuperStructure.SuperStructurePose.SCORE_L4)))
                .onTrue(coralHolder.keepCoralShuffledForever());

        // Prepare Level 4 score
        operator.povLeft().onTrue(coralHolder.prepareToScoreL4());

        // Prepare Level 4 score
        driver.getController().button(8).onTrue(coralHolder.prepareToScoreL4());

        // Retrieve elevator at the start of teleop
        new Trigger(DriverStation::isTeleopEnabled)
                .onTrue(superStructure.moveToPose(SuperStructure.SuperStructurePose.IDLE));

        // Retrieve elevator when robot is about to tip
        drive.driveTrainTipping
                .onTrue(superStructure.moveToPose(SuperStructure.SuperStructurePose.IDLE))
                .onTrue(ledStatusLight
                        .playAnimation(new LEDAnimation.Breathe(Color.kRed), 0.25, 4)
                        .ignoringDisable(true));
        // score
        operator.rightTrigger(0.5).whileTrue(coralHolder.scoreCoral(3.0));

        operator.povDown()
                .and(operator.leftBumper().or(isAlgaeMode))
                .onTrue(superStructure.moveToPose(SuperStructure.SuperStructurePose.LOW_ALGAE));
        operator.povUp()
                .and(operator.leftBumper().or(isAlgaeMode))
                .onTrue(superStructure.moveToPose(SuperStructure.SuperStructurePose.HIGH_ALGAE));

        // push algae
        operator.rightBumper()
                .and(isAlgaeMode)
                .onTrue(superStructure.moveToPose(SuperStructure.SuperStructurePose.SCORE_ALGAE));
        operator.leftTrigger(0.5).and(isAlgaeMode).onTrue(coralHolder.runVolts(-3, 0));
        operator.rightTrigger(0.5).and(isAlgaeMode).whileTrue(coralHolder.runVolts(6, 0));
        isAlgaeMode.onFalse(coralHolder.runVolts(-2, 0).withTimeout(0.5));
        operator.back().whileTrue(coralHolder.runVolts(-0.5, -6));

        /* auto alignment reef part*/
        driver.selectReefPart0Button()
                .onTrue(ReefAlignment.selectReefPartButton(0).ignoringDisable(true));
        ;
        driver.selectReefPart1Button()
                .onTrue(ReefAlignment.selectReefPartButton(1).ignoringDisable(true));
        ;
        driver.selectReefPart2Button()
                .onTrue(ReefAlignment.selectReefPartButton(2).ignoringDisable(true));
        ;
        driver.selectReefPart3Button()
                .onTrue(ReefAlignment.selectReefPartButton(3).ignoringDisable(true));
        ;
        driver.selectReefPart4Button()
                .onTrue(ReefAlignment.selectReefPartButton(4).ignoringDisable(true));
        ;
        driver.selectReefPart5Button()
                .onTrue(ReefAlignment.selectReefPartButton(5).ignoringDisable(true));
        ;

        /* auto alignment example, delete it for your project */
        driver.autoAlignmentButtonLeft()
                .whileTrue(ReefAlignment.alignmentToBranch(
                        drive, aprilTagVision, ledStatusLight, driver, false, Commands::none));
        driver.autoAlignmentButtonRight()
                .whileTrue(ReefAlignment.alignmentToBranch(
                        drive, aprilTagVision, ledStatusLight, driver, true, Commands::none));

        //        operator.y().onTrue(ReefAlignment.selectReefPartButton(3).ignoringDisable(true));
        //        operator.a().onTrue(ReefAlignment.selectReefPartButton(0).ignoringDisable(true));
        //        operator.x().whileTrue(ReefAlignment.lefterTargetButton(0.3).ignoringDisable(true));
        //        operator.b().whileTrue(ReefAlignment.righterTargetButton(0.3).ignoringDisable(true));
    }

    public void configureLEDEffects() {
        ledStatusLight.setDefaultCommand(ledStatusLight.showEnableDisableState());
        coralHolder.hasCoral.onTrue(ledStatusLight.playAnimation(new LEDAnimation.Breathe(Color.kYellow), 0.2, 4));
    }

    /**
     * Use this to pass the autonomous command to the main {@link Robot} class.
     *
     * @return the command to run in autonomous
     */
    public Command getAutonomousCommand() {
        return autonomousCommand;
    }

    public Command getTestCommand() {
        return testChooser.getSelected().get();
    }

    public void updateFieldSimAndDisplay() {
        if (driveSimulation == null) return;

        SimulatedArena.getInstance().simulationPeriodic();
        Logger.recordOutput("FieldSimulation/RobotPosition", driveSimulation.getSimulatedDriveTrainPose());
        Logger.recordOutput(
                "FieldSimulation/Algae", SimulatedArena.getInstance().getGamePiecesArrayByType("Algae"));
        Logger.recordOutput(
                "FieldSimulation/Coral", SimulatedArena.getInstance().getGamePiecesArrayByType("Coral"));
    }

    private final Alert autoPlacementIncorrect = AlertsManager.create(
            "Expected Autonomous robot placement position does not match reality, IS THE SELECTED AUTO CORRECT?",
            Alert.AlertType.kWarning);
    private static final double AUTO_PLACEMENT_TOLERANCE_METERS = 0.25;
    private static final double AUTO_PLACEMENT_TOLERANCE_DEGREES = 5;

    public void updateTelemetryAndLED() {
        field.setRobotPose(
                Robot.CURRENT_ROBOT_MODE == RobotMode.SIM
                        ? driveSimulation.getSimulatedDriveTrainPose()
                        : drive.getPose());
        if (Robot.CURRENT_ROBOT_MODE == RobotMode.SIM)
            field.getObject("Odometry").setPose(drive.getPose());

        ReefAlignment.updateDashboard();

        SuperStructureVisualizer.visualizeMechanisms(
                "measuredMechanismPoses", elevator.getHeightMeters(), arm.getArmAngle());
        SuperStructureVisualizer.visualizeMechanisms(
                "profileCurrentStatePoses", elevator.getProfileCurrentStateMeters(), arm.getProfileCurrentState());
        Logger.recordOutput("SuperStructure/currentPose", superStructure.currentPose());

        Pose2d autoStartingPose =
                FieldMirroringUtils.toCurrentAlliancePose(previouslySelectedAuto.getStartingPoseAtBlueAlliance());
        Pose2d currentPose = RobotState.getInstance().getVisionPose();
        Transform2d difference = autoStartingPose.minus(currentPose);
        boolean autoPlacementIncorrectDetected = difference.getTranslation().getNorm() > AUTO_PLACEMENT_TOLERANCE_METERS
                || Math.abs(difference.getRotation().getDegrees()) > AUTO_PLACEMENT_TOLERANCE_DEGREES;
        // autoPlacementIncorrect.set(autoPlacementIncorrectDetected && DriverStation.isDisabled());
        autoPlacementIncorrect.set(false);

        AlertsManager.updateLEDAndLog(ledStatusLight);
    }

    private boolean motorBrakeEnabled = false;

    public void setMotorBrake(boolean brakeModeEnabled) {
        if (this.motorBrakeEnabled == brakeModeEnabled) return;

        System.out.println("Set motor brake: " + brakeModeEnabled);
        drive.setMotorBrake(brakeModeEnabled);
        arm.setMotorBrake(brakeModeEnabled);
        elevator.setMotorBrake(brakeModeEnabled);
        if (brakeModeEnabled) ledStatusLight.showEnableDisableState().schedule();
        else
            ledStatusLight
                    .playAnimationPeriodically(new LEDAnimation.Breathe(Color.kWhite), 1)
                    .ignoringDisable(true)
                    .schedule();

        this.motorBrakeEnabled = brakeModeEnabled;
    }
}
