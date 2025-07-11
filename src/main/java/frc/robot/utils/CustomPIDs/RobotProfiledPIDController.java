package frc.robot.utils.CustomPIDs;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.trajectory.TrapezoidProfile;

public class RobotProfiledPIDController extends ProfiledPIDController {
    private final RobotPIDController.RobotPIDConfig pidConfig;

    public RobotProfiledPIDController(
            RobotPIDController.RobotPIDConfig pidConfig, TrapezoidProfile.Constraints constraints) {
        super(pidConfig.Kp, pidConfig.Ki, pidConfig.Kd, constraints);
        this.pidConfig = pidConfig;
        if (pidConfig.isCircularLoop) super.enableContinuousInput(-Math.PI, Math.PI);
    }

    @Override
    public double calculate(double measurement) {
        return MathUtil.clamp(super.calculate(measurement), -pidConfig.maximumPower, pidConfig.maximumPower);
    }
}
