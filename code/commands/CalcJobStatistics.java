package com.socialvagrancy.blackpearl.logs.commands;

import com.socialvagrancy.blackpearl.logs.structures.CompletedJob;
import com.socialvagrancy.blackpearl.logs.structures.JobStatistics;
import com.socialvagrancy.blackpearl.logs.structures.TapeActivity;
import com.socialvagrancy.blackpearl.logs.structures.TapeExchange;
import com.socialvagrancy.blackpearl.logs.structures.TapeJob;
import com.socialvagrancy.blackpearl.logs.structures.Task;
import com.socialvagrancy.utils.storage.UnitConverter;

import java.math.BigInteger;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.TreeMap;

public class CalcJobStatistics
{
	public static ArrayList<JobStatistics> attachTapeExchanges(ArrayList<JobStatistics> stat_list, HashMap<String, TreeMap<LocalDateTime, TapeExchange>> exchange_map)
	{
		//=================================================================
		// Attach the tape exchange to the job statistic.
		// 	As the tape write appears to be correctly attached, we'll
		// 	try to find a tape mount that occurs between the job 
		// 	creation timestamp (start_time = created_at) and the time
		// 	the job starts processing (data_time = tape_data_start).
		//=================================================================

		DateTimeFormatter string_format = DateTimeFormatter.ofPattern("yyyy MMM dd HH:mm:ss", Locale.ENGLISH);
		String tape_drive;
		String created_at;
		String date_completed;
		LocalDateTime start_time;
		LocalDateTime data_time;
		LocalDateTime mount_time;
		LocalDateTime eject_time;
		TapeExchange exchange;

		for(int i=0; i < stat_list.size(); i++)
		{
			// Create datetime variables for the important time marks.
			// 	This allows search the treemap for closest timestamp.
			start_time = LocalDateTime.parse(LocalDateTime.now().getYear() + " " + stat_list.get(i).created_at, string_format);
			data_time = LocalDateTime.parse(LocalDateTime.now().getYear() + " " + stat_list.get(i).tape_data_start, string_format);

			// Find the tape mount that started before data started being written to the drive.
			// As data can't be written to an empty drive, the assumption is this move has to be
			// a tape mount.
			mount_time = exchange_map.get(stat_list.get(i).tape_drive_sn).lowerKey(data_time);
			
			// Ejection should be the move before the exchange identified above. Tapes can't be
			// moved to a drive that is already occupied, so this has to be the eject call. 
			eject_time = exchange_map.get(stat_list.get(i).tape_drive_sn).lowerKey(mount_time);

			exchange = exchange_map.get(stat_list.get(i).tape_drive_sn).get(mount_time);

			// Identify the drive number.
			// 	This is grabbed off of the slot. Drive count starts at slot 256 
			// 	for some weird reason with drive 256 being drive 1. This exchange is used 
			// 	regardless of whether of the tape was already loaded, so checks must be
			// 	made for mounts and ejects.
			if(exchange.toDrive)
			{
				tape_drive = String.valueOf(Integer.valueOf(exchange.target)-255);
			}
			else
			{
				tape_drive = String.valueOf(Integer.valueOf(exchange.source)-255);
			}
		
			stat_list.get(i).tape_drive_id = tape_drive;
			stat_list.get(i).tape_barcode = exchange.tape_barcode;

			if(mount_time.isAfter(start_time))
			{
				// if the move occurred after the start_time, it was the result of the job.
				
				stat_list.get(i).wasMounted = false;
				stat_list.get(i).mount_start = exchange.start_time;
				stat_list.get(i).mount_end = exchange.end_time;
				stat_list.get(i).mount_duration = findDuration(exchange.start_time, exchange.end_time);
				
				// SWITCH EXCHANGE TO EJECT CALL
				exchange = exchange_map.get(stat_list.get(i).tape_drive_sn).get(eject_time);

				stat_list.get(i).eject_call = exchange.prepare_time;
				stat_list.get(i).eject_start = exchange.start_time;
				stat_list.get(i).eject_end = exchange.end_time;
				stat_list.get(i).eject_duration = findDuration(exchange.start_time, exchange.end_time);
			}
			else
			{
				// if the move occurred before the start_time, it was not a result of the job.
				
				stat_list.get(i).wasMounted = true;
				stat_list.get(i).eject_call = "00:00:00";
				stat_list.get(i).eject_start = "00:00:00";
				stat_list.get(i).eject_end = "00:00:00";
				stat_list.get(i).eject_duration = "00:00:00";
				stat_list.get(i).mount_start = "00:00:00";
				stat_list.get(i).mount_end = "00:00:00";
				stat_list.get(i).mount_duration = "00:00:00";
			}
			
		}

		return stat_list;
	}

	public static ArrayList<JobStatistics> attachTapeJobs(ArrayList<JobStatistics> stat_list, HashMap<String, TreeMap<LocalDateTime, TapeJob>> job_map) 
	{
		DateTimeFormatter string_format = DateTimeFormatter.ofPattern("yyyy MMM dd HH:mm:ss", Locale.ENGLISH);

		String tape_drive;
		String created_at;
		String date_completed;
		LocalDateTime start_time;
		LocalDateTime end_time;
		LocalDateTime job_time;
		LocalDateTime job_end;
		LocalDateTime test_time;
		TapeJob job;

		for(int i=0; i < stat_list.size(); i++)
		{
			start_time = LocalDateTime.parse(LocalDateTime.now().getYear() + " " + stat_list.get(i).created_at, string_format);
			end_time = LocalDateTime.parse(LocalDateTime.now().getYear() + " " + stat_list.get(i).date_completed, string_format);
	
			
			test_time = end_time;
			int year = LocalDateTime.now().getYear();

			do
			{
				job_time = job_map.get(stat_list.get(i).tape_drive_sn).lowerKey(test_time);

				job = job_map.get(stat_list.get(i).tape_drive_sn).get(job_time);
				
				job_end = LocalDateTime.parse(year + " " + job.end_time, string_format);

				test_time = job_time; // Set this in case we hit the while.
			
			} while(job_end.isAfter(end_time));

			stat_list.get(i).tape_data_start = job.start_time;
			stat_list.get(i).tape_data_end = job.end_time;
			stat_list.get(i).tape_data_duration = job.duration;
		}

		return stat_list;
	}

	public static ArrayList<JobStatistics> calculate(String log_path)
	{
		CompletedJob jobs = GetCompletedJobs.fromJson(log_path + "gui_ds3_completed_jobs.json");
		ArrayList<Task> task_list = GetTapeTasks.fromDataPlannerMain(log_path + "var.log.dataplanner-main.log");		
		HashMap<String, ArrayList<String>> job_id_chunk_map = GetJobIDandChunks.fromDataplannerMain(log_path + "var.log.dataplanner-main.log");
		ArrayList<TapeExchange> exchange_list = GetTapeExchanges.fromTapeBackend(log_path + "var.log.tape_backend.log");
		ArrayList<TapeJob> job_list = GetTapeJobs.fromTapeBackend(log_path + "var.log.tape_backend.log");

		if(task_list.size() > 0 && jobs.data.length > 0)
		{	
			HashMap<String, Task> task_map = mapTasksToChunks(task_list);

			ArrayList<JobStatistics> stat_list = initializeStatList(jobs, task_map, job_id_chunk_map);
		
			HashMap<String, TreeMap<LocalDateTime, TapeExchange>> exchange_map = mapActivity(exchange_list);
			HashMap<String, TreeMap<LocalDateTime, TapeJob>> job_map = mapActivity(job_list);

			stat_list = attachTapeJobs(stat_list, job_map);
			stat_list = attachTapeExchanges(stat_list, exchange_map);
	
			print(stat_list);

			return stat_list;
		}
			
		return null;
	}

	public static String findDuration(String start_date, String end_date)
	{
		int year = LocalDateTime.now().getYear();

		DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy MMM dd HH:mm:ss", Locale.ENGLISH);
		
		LocalDateTime start = LocalDateTime.parse((year + " " + start_date), format);
		LocalDateTime end = LocalDateTime.parse((year + " " + end_date), format);

		Duration duration = Duration.between(start, end);

		long seconds = duration.toSeconds() % 60;
		long minutes = duration.toMinutes() % 60;
		long hours = duration.toHours();

		return hours + ":" + minutes + ":" + seconds;
	}

	public static String findWriteSpeed(BigInteger size_in_bytes, String dur)
	{
		String[] time_comp = dur.split(":");
		Duration duration = Duration.parse("PT" + time_comp[0] + "H" + time_comp[1] + "M" + time_comp[2] + "S");
		BigInteger speed = size_in_bytes.divide(new BigInteger(String.valueOf(duration.getSeconds())));

		return UnitConverter.bytesToHumanReadable(speed) + "/s";

	}

	public static ArrayList<JobStatistics> initializeStatList(CompletedJob jobs, HashMap<String, Task> task_map, HashMap<String, ArrayList<String>> job_id_chunk_map)
	{
		DateTimeFormatter json_format = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss.SSS", Locale.ENGLISH);
		DateTimeFormatter string_format = DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.ENGLISH);
		String created_at;
		String date_completed;
		
		ArrayList<JobStatistics> stat_list = new ArrayList<JobStatistics>();
		JobStatistics stat;
	
		String job_id;
		String chunk_id;

		for(int i=0; i<jobs.data.length; i++)
		{
			job_id = jobs.data[i].id;
			if(job_id_chunk_map.get(job_id) != null)
			{
				for(int j=0; j<job_id_chunk_map.get(job_id).size(); j++)
				{
					
					chunk_id = job_id_chunk_map.get(job_id).get(j);
					
					// Check to see if there is a WriteChunkToTapeTask or ReadChunkFromTapeTask before initializing.
					// This will filter out all the jobs without data in the logs.
					if(task_map.get(chunk_id) != null)
					{
						stat = new JobStatistics();

						// Format JSON data values to something the computer actually recognizes as a timestamp.
						created_at = jobs.data[i].created_at.replace('T', '_'); // Remove the T separating date and time.
						created_at = created_at.substring(0, created_at.length()-1); // Remove the trailing Z
						date_completed = jobs.data[i].date_completed.replace('T', '_'); // Remove the T separating date and time.
						date_completed = date_completed.substring(0, date_completed.length()-1); // Remove the trailing Z.

						stat.job_id = jobs.data[i].id;
						stat.job_name = jobs.data[i].name;
						stat.created_at = LocalDateTime.parse(created_at, json_format).format(string_format);
						stat.date_completed = LocalDateTime.parse(date_completed, json_format).format(string_format);
						stat.job_duration = findDuration(LocalDateTime.parse(created_at, json_format).format(string_format), LocalDateTime.parse(date_completed, json_format).format(string_format));
						stat.request_type = jobs.data[i].request_type;
						stat.size_in_bytes = new BigInteger(jobs.data[i].original_size_in_bytes);
						stat.human_readable_size = UnitConverter.bytesToHumanReadable(stat.size_in_bytes);
						stat.chunk_id = job_id_chunk_map.get(job_id).get(j);
						stat.tape_drive_sn = task_map.get(chunk_id).drive_sn;
						stat.tape_write_speed = task_map.get(chunk_id).throughput;

						// Filter out values without a tape drive as these aren't able to be linked
						// to the tape_backend log.
						if(stat.tape_drive_sn != null)
						{	
							stat_list.add(stat);
						}
					}
				}
			}
		}

		return stat_list;
	}

	public static <T extends TapeActivity> HashMap<String, TreeMap<LocalDateTime, T>> mapActivity(ArrayList<T> action_list)
	{
		HashMap<String, TreeMap<LocalDateTime, T>> action_map = new HashMap<String, TreeMap<LocalDateTime, T>>();
		TreeMap<LocalDateTime, T> log_tree = null;
		LocalDateTime timestamp;
		String compare_drive = "empty"; // value to be compared to split the array down into individual tree maps.
		int year = LocalDateTime.now().getYear();
		String date;

		// Order the array list by tape drive.
		Collections.sort(action_list, (T t1, T t2) -> t1.drive_sn.compareTo(t2.drive_sn));

		for(int i=0; i < action_list.size(); i++)
		{
			// Add log_tree to hashmap and create new log_tree
			if(!action_list.get(i).drive_sn.equals(compare_drive))
			{
				if(log_tree != null)
				{
					action_map.put(compare_drive, log_tree);
				}

				compare_drive = action_list.get(i).drive_sn;
				log_tree = new TreeMap<LocalDateTime, T>();
			}

			if(action_list.get(i).start_time != null)
			{
				// Convert the start_time into a time_stamp for searching in the tree.
				// As the logs don't include a year field, the current year is grabbed
				// from the file.
				date = year + " " + action_list.get(i).start_time;
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy MMM dd HH:mm:ss", Locale.ENGLISH);
				timestamp = LocalDateTime.parse(date, formatter);

				//********************************************************
				// ISSUE
				// 	Does not handle logs that span a year.
				// 	Maybe put a check between this date and the 
				// 	last date to see if we're more than a month forware
				// 	and subtract 1 from year if so?
				//********************************************************
				// END ISSUE
				//********************************************************
			
				log_tree.put(timestamp, action_list.get(i));
			}
		}
	
		// Add the last tree
		action_map.put(compare_drive, log_tree);

		return action_map;
	}
	
	public static HashMap<String, Task> mapTasksToChunks(ArrayList<Task> task_list)
	{
		HashMap<String, Task> task_map = new HashMap<String, Task>();

		for(int i=0; i < task_list.size(); i++)
		{
			if(task_list.get(i).chunk_id != null)
			{
				for(int j=0; j < task_list.get(i).chunk_id.length; j++)
				{
					task_map.put(task_list.get(i).chunk_id[j], task_list.get(i));
				}
			}
		}

		return task_map;
	}

	public static void main(String[] args)
	{
		CalcJobStatistics stats = new CalcJobStatistics();

		stats.calculate("../logs/");
	}

	public static void print(ArrayList<JobStatistics> stat_list)
	{
		System.out.println("job_id,job_name,request_type,size,created_at,date_completed,job_duration,chunk_id,drive_num,drive_sn,bar_code,already_mounted,eject_call,eject_start,eject_finish,eject_duration,mount_start,mount_finish,mount_duration,write_start,write_finish,write_duration,write_speed");
		
		for(int i=0; i<stat_list.size(); i++)
		{
			System.out.print(stat_list.get(i).job_id + ",");
			System.out.print(stat_list.get(i).job_name + ",");
			System.out.print(stat_list.get(i).request_type + ",");
			System.out.print(stat_list.get(i).human_readable_size + ",");
			System.out.print(stat_list.get(i).created_at + ",");
			System.out.print(stat_list.get(i).date_completed + ",");
			System.out.print(stat_list.get(i).job_duration + ",");
			System.out.print(stat_list.get(i).chunk_id + ",");
			System.out.print(stat_list.get(i).tape_drive_id + ",");
			System.out.print(stat_list.get(i).tape_drive_sn + ",");
			System.out.print(stat_list.get(i).tape_barcode + ",");

			if(stat_list.get(i).wasMounted)
			{
				System.out.print("TRUE,");
			}
			else
			{
				System.out.print("FALSE,");
			}

			System.out.print(stat_list.get(i).eject_call + ",");
			System.out.print(stat_list.get(i).eject_start + ",");
			System.out.print(stat_list.get(i).eject_end + ",");
			System.out.print(stat_list.get(i).eject_duration + ",");
			System.out.print(stat_list.get(i).mount_start + ",");
			System.out.print(stat_list.get(i).mount_end + ",");
			System.out.print(stat_list.get(i).mount_duration + ",");
			System.out.print(stat_list.get(i).tape_data_start + ",");
			System.out.print(stat_list.get(i).tape_data_end + ",");
			System.out.print(stat_list.get(i).tape_data_duration + ",");
			System.out.print(stat_list.get(i).tape_write_speed);
			System.out.print("\n");
		}
	}

}