package frc.robot.subsystems;

import com.ctre.phoenix.sensors.AbsoluteSensorRange;
import com.ctre.phoenix.sensors.CANCoderConfiguration;
import com.ctre.phoenix.sensors.WPI_CANCoder;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMax.ControlType;
import com.revrobotics.CANSparkMax.IdleMode;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;
import com.revrobotics.RelativeEncoder;
import com.revrobotics.SparkMaxPIDController;

import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import frc.robot.Constants.kDrive;
import frc.robot.Constants.kDrive.kRelativeEncoder;

/**
 * SDS MK4i Swerve Module
 * NEO Pinions, L2
 * Product page: https://www.swervedrivespecialties.com/products/mk4i-swerve-module?variant=39598777303153
 */
public class SwerveModule {

    // Motors
    private final CANSparkMax mot_drive;
    private final CANSparkMax mot_turn;

    // Encoders
    private final RelativeEncoder enc_drive;
    private final RelativeEncoder enc_turn;
    private final WPI_CANCoder enc_cancoder;
    private final CANCoderConfiguration m_cancoderConfiguration;

    // PID Controllers
    private final SparkMaxPIDController m_drivePIDController;
    private final SparkMaxPIDController m_turnPIDController;


    public SwerveModule(int driveMotorID, int turnMotorID,
        int cancoderID, double cancoderAbsoluteOffset,
        boolean driveMotorInverted, boolean turnMotorInverted) {

        // Motors
        mot_drive = new CANSparkMax(driveMotorID, MotorType.kBrushless);
        mot_turn = new CANSparkMax(turnMotorID, MotorType.kBrushless);

        // Encoders
        enc_drive = mot_drive.getEncoder();
        enc_turn = mot_turn.getEncoder();
        enc_cancoder = new WPI_CANCoder(cancoderID);
        m_cancoderConfiguration = new CANCoderConfiguration();
        configEncoder(cancoderAbsoluteOffset);

        // PID Controllers
        // Drive
        m_drivePIDController = mot_drive.getPIDController();
        m_drivePIDController.setP(kDrive.kTurnP);
        m_drivePIDController.setI(kDrive.kTurnI);
        m_drivePIDController.setD(kDrive.kTurnD);
        m_drivePIDController.setFF(kDrive.kDriveFF);
        // Turn
        m_turnPIDController = mot_turn.getPIDController();
        m_turnPIDController.setP(kDrive.kTurnP);
        m_turnPIDController.setI(kDrive.kTurnI);
        m_turnPIDController.setD(kDrive.kTurnD);
        m_turnPIDController.setFF(kDrive.kTurnFF);
        m_turnPIDController.setSmartMotionMaxAccel(kDrive.kMaxTurnAngularAcceleration, 0);
        m_turnPIDController.setPositionPIDWrappingEnabled(true);
        m_turnPIDController.setPositionPIDWrappingMaxInput(Math.PI);
        m_turnPIDController.setPositionPIDWrappingMinInput(-Math.PI);

        configMotors(driveMotorInverted, turnMotorInverted);
    }

    /**
     * Config the drive and turn motors.
     * 
     * - Set brake mode
     * - Set inverted (if applicable)
     * - Set current limit
     */
    private void configMotors(boolean driveMotorInverted, boolean turnMotorInverted) {
        mot_drive.restoreFactoryDefaults();
        mot_drive.setIdleMode(IdleMode.kBrake);
        mot_drive.setInverted(driveMotorInverted);
        mot_drive.setSmartCurrentLimit(kDrive.kDriveMotorCurrentLimit);
        mot_drive.burnFlash();

        mot_turn.restoreFactoryDefaults();
        mot_turn.setIdleMode(IdleMode.kBrake);
        mot_turn.setInverted(turnMotorInverted);
        mot_turn.setSmartCurrentLimit(kDrive.kTurnMotorCurrentLimit);
        mot_turn.burnFlash();
    }

    /**
     * Configure the drive and turn CANCoders.
     */
    private void configEncoder(double cancoderAbsoluteOffset) {
        enc_drive.setVelocityConversionFactor(kRelativeEncoder.kDriveSensorCoefficient * 60);
        enc_drive.setPositionConversionFactor(kRelativeEncoder.kDriveSensorCoefficient * 60);
        enc_turn.setVelocityConversionFactor(kRelativeEncoder.kTurnSensorCoefficient * 60);
        enc_turn.setPositionConversionFactor(kRelativeEncoder.kTurnSensorCoefficient * 60);
        
        m_cancoderConfiguration.magnetOffsetDegrees = cancoderAbsoluteOffset;
        m_cancoderConfiguration.absoluteSensorRange = AbsoluteSensorRange.Signed_PlusMinus180;
        enc_cancoder.configAllSettings(m_cancoderConfiguration);

        resetEncoders();
    }

    public void stopMotors() {
        mot_drive.set(0);
        mot_turn.set(0);
    }

    /**
     * Set drive and turning encoder positions to zero.
     */
    public void resetEncoders() {
        enc_drive.setPosition(0);
        enc_turn.setPosition(getAbsoluteTurnEncoderPosition());
    }

    /**
     * Return the absolute turn encoder position in meters.
     * 
     * Equation:
     * circumference * (angle / 360)
     * 
     * @return absolute turn encoder position
     */
    public double getAbsoluteTurnEncoderPosition() {
        return kDrive.kWheelCircumference * (enc_cancoder.getAbsolutePosition() / 360);
    }

    /**
     * @return current state of the module
     */
    public SwerveModuleState getState() {
        return new SwerveModuleState(enc_drive.getVelocity(), new Rotation2d(enc_turn.getPosition()));
    }

    /**
     * @return current position of the module
     */
    public SwerveModulePosition getPosition() {
        return new SwerveModulePosition(enc_drive.getPosition(), new Rotation2d(enc_turn.getPosition()));
    }

    /**
     * Set desired state of the module.
     * 
     * @param desiredState desired state of the module
     */
    public void setDesiredState(SwerveModuleState desiredState) {

        // If no velocity, don't set new module state.
        // This will prevent the wheels from turning back to straight
        // each time after moving.
        if (Math.abs(desiredState.speedMetersPerSecond) < 0.001) {
            stopMotors();
            return;
        }

        // Optimize reference state
        SwerveModuleState optimizedState = SwerveModuleState.optimize(desiredState, new Rotation2d(enc_turn.getPosition()));

        // Drive output
        m_drivePIDController.setReference(optimizedState.speedMetersPerSecond, ControlType.kVelocity, 0);
        m_turnPIDController.setReference(optimizedState.angle.getRadians(), ControlType.kVelocity, 0);
    }
    
}
