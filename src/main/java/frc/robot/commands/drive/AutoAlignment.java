package frc.robot.commands.drive;

import static edu.wpi.first.units.Units.*;
import static frc.robot.constants.DriveControlLoops.*;

import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.path.*;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.units.measure.Distance;
import edu.wpi.first.units.measure.LinearAcceleration;
import edu.wpi.first.units.measure.LinearVelocity;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import frc.robot.RobotState;
import frc.robot.subsystems.drive.HolonomicDriveSubsystem;
import frc.robot.subsystems.vision.apriltags.AprilTagVision;
import frc.robot.utils.ChassisHeadingController;
import frc.robot.utils.FieldMirroringUtils;
import frc.robot.utils.PathUtils;
import java.util.*;

public class AutoAlignment {
    public record AutoAlignmentTarget(
            Pose2d roughTarget,
            Pose2d preciseTarget,
            Rotation2d preciseApproachDirection,
            Optional<Translation2d> faceToTargetDuringRoughApproach,
            OptionalInt tagIdToFocus,
            Integer... cameraToFocus) {}
    /**
     * creates a precise auto-alignment command NOTE: AutoBuilder must be configured! the command has two steps: 1.
     * path-find to the target pose, roughly 2. accurate auto alignment
     */
    public static Command pathFindAndAutoAlign(
            HolonomicDriveSubsystem driveSubsystem,
            AprilTagVision vision,
            AutoAlignmentTarget target,
            Command toRunDuringRoughApproach,
            Command toRunDuringPreciseAlignment,
            AutoAlignmentConfigurations config) {
        Command pathFindToRoughTarget = pathFindToPose(
                        target.roughTarget(), target.faceToTargetDuringRoughApproach(), config)
                .onlyIf(() -> RobotState.getInstance()
                                .getVisionPose()
                                .minus(target.preciseTarget())
                                .getTranslation()
                                .getNorm()
                        > config.distanceStartPreciseApproach.in(Meters));
        Command preciseAlignment = preciseAlignment(
                        driveSubsystem, target.preciseTarget(), target.preciseApproachDirection(), config)
                .deadlineFor(vision.focusOnTarget(target.tagIdToFocus(), target.cameraToFocus()));

        return pathFindToRoughTarget
                .deadlineFor(toRunDuringRoughApproach.asProxy())
                .andThen(preciseAlignment.deadlineFor(toRunDuringPreciseAlignment.asProxy()));
    }

    public static Command followPathAndAutoAlign(
            HolonomicDriveSubsystem driveSubsystem,
            AprilTagVision vision,
            PathPlannerPath path,
            Pose2d preciseTargetAtBlue,
            Rotation2d preciseTargetApproachDirection,
            OptionalInt tagIdToFocusAtBlue,
            OptionalInt tagIdToFocusAtRed,
            Integer[] cameraIdToFocus,
            AutoAlignmentConfigurations config,
            Command... toScheduleAtPreciseAlignment) {
        return Commands.deferredProxy(() -> followPathAndAutoAlignStatic(
                driveSubsystem,
                vision,
                path,
                new AutoAlignmentTarget(
                        PathUtils.getEndingPose(path),
                        FieldMirroringUtils.toCurrentAlliancePose(preciseTargetAtBlue),
                        preciseTargetApproachDirection,
                        Optional.empty(),
                        FieldMirroringUtils.isSidePresentedAsRed() ? tagIdToFocusAtRed : tagIdToFocusAtBlue,
                        cameraIdToFocus),
                config,
                toScheduleAtPreciseAlignment));
    }

    public static Command followPathAndAutoAlignStatic(
            HolonomicDriveSubsystem driveSubsystem,
            AprilTagVision vision,
            PathPlannerPath path,
            AutoAlignmentTarget target,
            AutoAlignmentConfigurations config,
            Command... toScheduleAtPreciseAlignment) {
        Command followPath = AutoBuilder.followPath(path)
                .until(() -> RobotState.getInstance()
                                .getVisionPose()
                                .getTranslation()
                                .minus(target.roughTarget().getTranslation())
                                .getNorm()
                        < config.distanceStartPreciseApproach.in(Meters));

        Command preciseAlignment = preciseAlignment(
                        driveSubsystem, target.preciseTarget(), target.preciseApproachDirection(), config)
                .deadlineFor(vision.focusOnTarget(target.tagIdToFocus(), target.cameraToFocus()))
                .finallyDo(driveSubsystem::stop);

        return followPath.andThen(preciseAlignment.beforeStarting(() -> {
            for (Command toSchedule : toScheduleAtPreciseAlignment) toSchedule.schedule();
        }));
    }

    public static Command pathFindToPose(
            Pose2d targetPose, Optional<Translation2d> faceToVisionTarget, AutoAlignmentConfigurations config) {
        ChassisHeadingController.ChassisHeadingRequest chassisHeadingRequest = faceToVisionTarget.isPresent()
                ? new ChassisHeadingController.FaceToTargetRequest(faceToVisionTarget::get, null)
                : new ChassisHeadingController.NullRequest();
        Command activateChassisHeadingController =
                Commands.runOnce(() -> ChassisHeadingController.getInstance().setHeadingRequest(chassisHeadingRequest));
        Runnable deactivateChassisHeadingController = () ->
                ChassisHeadingController.getInstance().setHeadingRequest(new ChassisHeadingController.NullRequest());

        PathConstraints normalConstraints = new PathConstraints(
                AUTO_ALIGNMENT_VELOCITY_LIMIT,
                AUTO_ALIGNMENT_ACCELERATION_LIMIT,
                ANGULAR_VELOCITY_SOFT_CONSTRAIN,
                ANGULAR_ACCELERATION_SOFT_CONSTRAIN);
        PathConstraints lowSpeedConstrain = new PathConstraints(
                MOVEMENT_VELOCITY_SOFT_CONSTRAIN_LOW,
                ACCELERATION_SOFT_CONSTRAIN_LOW,
                ANGULAR_VELOCITY_SOFT_CONSTRAIN,
                ANGULAR_ACCELERATION_SOFT_CONSTRAIN);
        Command pathFindToPoseNormalConstrains = AutoBuilder.pathfindToPose(targetPose, normalConstraints)
                .onlyIf(() -> !RobotState.getInstance().lowSpeedModeEnabled())
                .until(RobotState.getInstance()::lowSpeedModeEnabled);
        Command pathFindToPoseLowConstrains = AutoBuilder.pathfindToPose(targetPose, lowSpeedConstrain)
                .onlyIf(RobotState.getInstance()::lowSpeedModeEnabled);
        Command pathFindToPose = pathFindToPoseNormalConstrains.andThen(pathFindToPoseLowConstrains);

        Runnable resetDriveCommandRotationMaintenance =
                () -> JoystickDrive.instance.ifPresent(joystickDrive -> joystickDrive.setRotationMaintenanceSetpoint(
                        RobotState.getInstance().getRotation()));

        return pathFindToPose
                .beforeStarting(activateChassisHeadingController)
                .until(() -> RobotState.getInstance()
                                .getVisionPose()
                                .getTranslation()
                                .minus(targetPose.getTranslation())
                                .getNorm()
                        < config.distanceStartPreciseApproach.in(Meters))
                .finallyDo(deactivateChassisHeadingController)
                .finallyDo(resetDriveCommandRotationMaintenance);
    }

    public static Command preciseAlignment(
            HolonomicDriveSubsystem driveSubsystem,
            Pose2d preciseTarget,
            Rotation2d preciseTargetApproachDirection,
            AutoAlignmentConfigurations config) {
        return Commands.defer(
                        () -> AutoBuilder.followPath(getPreciseAlignmentPath(
                                driveSubsystem.getMeasuredChassisSpeedsFieldRelative(),
                                driveSubsystem.getPose(),
                                preciseTarget,
                                preciseTargetApproachDirection,
                                config)),
                        Set.of(driveSubsystem))
                .deadlineFor(RobotState.getInstance().withNavigationMode(RobotState.NavigationMode.VISION_GUIDED));
    }

    public static PathPlannerPath getPreciseAlignmentPath(
            ChassisSpeeds measuredSpeedsFieldRelative,
            Pose2d currentRobotPose,
            Pose2d preciseTarget,
            Rotation2d preciseTargetApproachDirection,
            AutoAlignmentConfigurations config) {
        Translation2d interiorWaypoint = preciseTarget
                .getTranslation()
                .plus(new Translation2d(
                        -config.finalApproachStraightTrajectoryLength.in(Meters), preciseTargetApproachDirection));
        Translation2d fieldRelativeSpeedsMPS = new Translation2d(
                measuredSpeedsFieldRelative.vxMetersPerSecond, measuredSpeedsFieldRelative.vyMetersPerSecond);
        Rotation2d startingPathDirection = interiorWaypoint
                .minus(currentRobotPose.getTranslation())
                .times(AUTO_ALIGNMENT_TRANSITION_COMPENSATION_FACTOR)
                .plus(fieldRelativeSpeedsMPS)
                .getAngle();

        List<Waypoint> waypoints = PathPlannerPath.waypointsFromPoses(
                new Pose2d(currentRobotPose.getTranslation(), startingPathDirection),
                new Pose2d(interiorWaypoint, preciseTargetApproachDirection),
                new Pose2d(preciseTarget.getTranslation(), preciseTargetApproachDirection));

        PathConstraints constraints = new PathConstraints(
                config.finalAlignmentSpeed(),
                config.preciseAlignmentMaxAcceleration(),
                ANGULAR_VELOCITY_SOFT_CONSTRAIN_LOW,
                ANGULAR_ACCELERATION_SOFT_CONSTRAIN_LOW);

        List<RotationTarget> rotationTargets = List.of(new RotationTarget(1.0, preciseTarget.getRotation()));

        PathPlannerPath path = new PathPlannerPath(
                waypoints,
                rotationTargets,
                List.of(),
                List.of(),
                List.of(),
                constraints,
                new IdealStartingState(fieldRelativeSpeedsMPS.getNorm(), currentRobotPose.getRotation()),
                new GoalEndState(config.hitTargetSpeed, preciseTarget.getRotation()),
                false);
        path.preventFlipping = true;

        return path;
    }

    public record AutoAlignmentConfigurations(
            Distance distanceStartPreciseApproach,
            LinearVelocity finalAlignmentSpeed,
            Distance finalApproachStraightTrajectoryLength,
            LinearVelocity hitTargetSpeed,
            LinearAcceleration preciseAlignmentMaxAcceleration) {
        public static final AutoAlignmentConfigurations DEFAULT_CONFIG = new AutoAlignmentConfigurations(
                Meters.of(0.5),
                MetersPerSecond.of(2),
                Meters.of(0.4),
                MetersPerSecond.of(0.5),
                MetersPerSecondPerSecond.of(4));
    }
}
